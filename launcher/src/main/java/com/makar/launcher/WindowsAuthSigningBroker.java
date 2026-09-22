package com.makar.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CountDownLatch;

final class WindowsAuthSigningBroker implements AutoCloseable {
    private static final int PIPE_ACCESS_DUPLEX = 0x00000003;
    private static final int FILE_FLAG_FIRST_PIPE_INSTANCE = 0x00080000;
    private static final int PIPE_TYPE_MESSAGE = 0x00000004;
    private static final int PIPE_READMODE_MESSAGE = 0x00000002;
    private static final int PIPE_NOWAIT = 0x00000001;
    private static final int ERROR_PIPE_CONNECTED = 535;
    private static final int ERROR_PIPE_LISTENING = 536;
    private static final int ERROR_NO_DATA = 232;
    private static final int BUFFER_SIZE = 8192;
    private static final long IO_TIMEOUT_MILLIS = 3_000;
    private static final long MAX_INITIAL_SESSION_FUTURE_SECONDS = 900;
    private static final long MAX_RECONNECT_SESSION_FUTURE_SECONDS = 10_860;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String pipeName;
    private final SigningPolicy policy;
    private final AtomicLong expectedPid = new AtomicLong(-1);
    private final AtomicBoolean running = new AtomicBoolean();
    private final CountDownLatch startupLatch = new CountDownLatch(1);
    private volatile WinNT.HANDLE pipeHandle;
    private volatile Thread thread;
    private volatile RuntimeException startupFailure;

    WindowsAuthSigningBroker(String pipeName, PrivateKey privateKey, GameAuthProtocol.ProofFields initialBinding) {
        if (OperatingSystem.current() != OperatingSystem.WINDOWS) {
            throw new IllegalStateException("Proof-of-possession authentication requires the Windows named-pipe broker.");
        }
        this.pipeName = pipeName;
        this.policy = new SigningPolicy(privateKey, initialBinding);
    }

    void start() {
        if (!running.compareAndSet(false, true)) return;
        thread = new Thread(this::runBroker, "deluxewarfare-auth-broker");
        thread.setDaemon(true);
        thread.start();
        try {
            if (!startupLatch.await(3, java.util.concurrent.TimeUnit.SECONDS)) {
                close();
                throw new IllegalStateException("Timed out while starting authentication named pipe.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            close();
            throw new IllegalStateException("Authentication broker startup was interrupted.", exception);
        }
        if (startupFailure != null || pipeHandle == null) {
            close();
            throw startupFailure == null
                    ? new IllegalStateException("Authentication named pipe did not start.")
                    : startupFailure;
        }
    }

    void bindMinecraftProcess(Process process) {
        if (process == null || !process.isAlive()) throw new IllegalArgumentException("Minecraft process is not alive.");
        expectedPid.set(process.pid());
        process.onExit().thenRun(this::close);
    }

    boolean acceptsClientPid(long actualPid) {
        return actualPid > 0 && actualPid == expectedPid.get();
    }

    String getPipeName() { return pipeName; }

    private void runBroker() {
        Pointer securityDescriptor = null;
        try {
            PointerByReference descriptorReference = new PointerByReference();
            if (!Advapi32Pipe.INSTANCE.ConvertStringSecurityDescriptorToSecurityDescriptorW(
                    new WString("D:P(A;;GA;;;OW)"), 1, descriptorReference, null)) {
                throw new IllegalStateException("Unable to create the named-pipe security descriptor: " + Native.getLastError());
            }
            securityDescriptor = descriptorReference.getValue();
            WinBase.SECURITY_ATTRIBUTES securityAttributes = new WinBase.SECURITY_ATTRIBUTES();
            securityAttributes.bInheritHandle = false;
            securityAttributes.lpSecurityDescriptor = securityDescriptor;
            securityAttributes.write();
            pipeHandle = Kernel32Pipe.INSTANCE.CreateNamedPipeW(
                    new WString(pipeName), PIPE_ACCESS_DUPLEX | FILE_FLAG_FIRST_PIPE_INSTANCE,
                    PIPE_TYPE_MESSAGE | PIPE_READMODE_MESSAGE | PIPE_NOWAIT, 1,
                    BUFFER_SIZE, BUFFER_SIZE, (int) IO_TIMEOUT_MILLIS, securityAttributes);
            if (pipeHandle == null || WinBase.INVALID_HANDLE_VALUE.equals(pipeHandle)) {
                throw new IllegalStateException("Unable to create authentication named pipe: " + Native.getLastError());
            }
            startupLatch.countDown();

            while (running.get()) {
                if (!connectClient()) continue;
                try {
                    IntByReference clientPid = new IntByReference();
                    if (!Kernel32Pipe.INSTANCE.GetNamedPipeClientProcessId(pipeHandle, clientPid)
                            || !acceptsClientPid(Integer.toUnsignedLong(clientPid.getValue()))) {
                        continue;
                    }
                    String request = readMessage();
                    if (request == null) continue;
                    writeMessage(handleRequest(request));
                } finally {
                    Kernel32Pipe.INSTANCE.DisconnectNamedPipe(pipeHandle);
                }
            }
        } catch (RuntimeException exception) {
            startupFailure = exception;
            startupLatch.countDown();
            if (running.get()) {
                System.err.println("Authentication broker stopped unexpectedly: " + exception.getMessage());
            }
        } finally {
            startupLatch.countDown();
            WinNT.HANDLE handle = pipeHandle;
            pipeHandle = null;
            if (handle != null && !WinBase.INVALID_HANDLE_VALUE.equals(handle)) Kernel32Pipe.INSTANCE.CloseHandle(handle);
            if (securityDescriptor != null) Kernel32Pipe.INSTANCE.LocalFree(securityDescriptor);
            policy.destroy();
            running.set(false);
        }
    }

    private boolean connectClient() {
        while (running.get()) {
            if (Kernel32Pipe.INSTANCE.ConnectNamedPipe(pipeHandle, Pointer.NULL)) return true;
            int error = Native.getLastError();
            if (error == ERROR_PIPE_CONNECTED) return true;
            if (error != ERROR_PIPE_LISTENING && error != ERROR_NO_DATA) return false;
            pauseBriefly();
        }
        return false;
    }

    private String readMessage() {
        byte[] bytes = new byte[BUFFER_SIZE];
        int offset = 0;
        long deadline = System.currentTimeMillis() + IO_TIMEOUT_MILLIS;
        while (running.get() && System.currentTimeMillis() < deadline && offset < bytes.length) {
            byte[] chunk = new byte[Math.min(1024, bytes.length - offset)];
            IntByReference read = new IntByReference();
            if (Kernel32Pipe.INSTANCE.ReadFile(pipeHandle, chunk, chunk.length, read, Pointer.NULL)) {
                for (int index = 0; index < read.getValue(); index++) {
                    if (chunk[index] == '\n') return new String(bytes, 0, offset, StandardCharsets.UTF_8);
                    bytes[offset++] = chunk[index];
                }
            } else if (Native.getLastError() != ERROR_NO_DATA) {
                return null;
            }
            pauseBriefly();
        }
        return null;
    }

    private void writeMessage(String message) {
        byte[] bytes = (message + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > BUFFER_SIZE) return;
        IntByReference written = new IntByReference();
        Kernel32Pipe.INSTANCE.WriteFile(pipeHandle, bytes, bytes.length, written, Pointer.NULL);
        Kernel32Pipe.INSTANCE.FlushFileBuffers(pipeHandle);
    }

    private String handleRequest(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            GameAuthProtocol.ProofFields fields = new GameAuthProtocol.ProofFields(
                    root.path("launchSessionId").asText(""), root.path("keyId").asText(""),
                    root.path("serverId").asText(""), root.path("serverAddress").asText(""),
                    root.path("playerUuid").asText(""), root.path("nickname").asText(""),
                    root.path("challenge").asText(""), root.path("expiresAt").asLong(0));
            String signature = policy.sign(fields);
            return objectMapper.writeValueAsString(new BrokerResponse(true, signature, ""));
        } catch (Exception exception) {
            try {
                return objectMapper.writeValueAsString(new BrokerResponse(false, "", "SIGNING_REQUEST_REJECTED"));
            } catch (Exception ignored) {
                return "{\"ok\":false,\"error\":\"SIGNING_REQUEST_REJECTED\"}";
            }
        }
    }

    private void pauseBriefly() {
        try { Thread.sleep(10); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }

    @Override
    public void close() {
        if (!running.getAndSet(false)) {
            policy.destroy();
            return;
        }
        WinNT.HANDLE handle = pipeHandle;
        if (handle != null && !WinBase.INVALID_HANDLE_VALUE.equals(handle)) Kernel32Pipe.INSTANCE.CloseHandle(handle);
        Thread current = thread;
        if (current != null) current.interrupt();
    }

    record BrokerResponse(boolean ok, String signature, String error) { }

    static final class SigningPolicy {
        private PrivateKey privateKey;
        private final GameAuthProtocol.ProofFields initial;
        private final Set<String> usedChallenges = new HashSet<>();
        private boolean initialUsed;

        SigningPolicy(PrivateKey privateKey, GameAuthProtocol.ProofFields initial) {
            this.privateKey = privateKey;
            this.initial = initial;
        }

        synchronized String sign(GameAuthProtocol.ProofFields fields) throws Exception {
            if (privateKey == null) throw new IllegalStateException("Broker is closed.");
            if (!fields.keyId().equals(initial.keyId()) || !fields.serverId().equals(initial.serverId())
                    || !GameAuthProtocol.canonicalizeServerAddress(fields.serverAddress()).equals(initial.serverAddress())
                    || !fields.playerUuid().equals(initial.playerUuid()) || !fields.nickname().equals(initial.nickname())) {
                throw new IllegalArgumentException("Proof binding mismatch.");
            }
            boolean initialSession = fields.launchSessionId().equals(initial.launchSessionId());
            long now = Instant.now().getEpochSecond();
            long maximumFutureSeconds = initialSession
                    ? MAX_INITIAL_SESSION_FUTURE_SECONDS
                    : MAX_RECONNECT_SESSION_FUTURE_SECONDS;
            if (fields.expiresAt() < now || fields.expiresAt() > now + maximumFutureSeconds) {
                throw new IllegalArgumentException("Proof expiry is invalid.");
            }
            if (!usedChallenges.add(fields.challenge())) throw new IllegalArgumentException("Challenge was already signed.");
            if (initialSession) {
                if (initialUsed || fields.expiresAt() != initial.expiresAt()) throw new IllegalArgumentException("Initial session was already signed.");
                initialUsed = true;
            }
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(privateKey);
            signer.update(GameAuthProtocol.canonicalPayload(fields).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        }

        synchronized void destroy() {
            privateKey = null;
            usedChallenges.clear();
        }
    }

    interface Kernel32Pipe extends StdCallLibrary {
        Kernel32Pipe INSTANCE = Native.load("kernel32", Kernel32Pipe.class, W32APIOptions.UNICODE_OPTIONS);
        WinNT.HANDLE CreateNamedPipeW(WString name, int openMode, int pipeMode, int maxInstances,
                                      int outBufferSize, int inBufferSize, int defaultTimeout,
                                      WinBase.SECURITY_ATTRIBUTES securityAttributes);
        boolean ConnectNamedPipe(WinNT.HANDLE pipe, Pointer overlapped);
        boolean DisconnectNamedPipe(WinNT.HANDLE pipe);
        boolean GetNamedPipeClientProcessId(WinNT.HANDLE pipe, IntByReference clientProcessId);
        boolean ReadFile(WinNT.HANDLE file, byte[] buffer, int bytesToRead, IntByReference bytesRead, Pointer overlapped);
        boolean WriteFile(WinNT.HANDLE file, byte[] buffer, int bytesToWrite, IntByReference bytesWritten, Pointer overlapped);
        boolean FlushFileBuffers(WinNT.HANDLE file);
        boolean CloseHandle(WinNT.HANDLE object);
        Pointer LocalFree(Pointer memory);
    }

    interface Advapi32Pipe extends StdCallLibrary {
        Advapi32Pipe INSTANCE = Native.load("advapi32", Advapi32Pipe.class, W32APIOptions.UNICODE_OPTIONS);
        boolean ConvertStringSecurityDescriptorToSecurityDescriptorW(WString descriptor, int revision,
                                                                      PointerByReference securityDescriptor,
                                                                      IntByReference securityDescriptorSize);
    }
}
