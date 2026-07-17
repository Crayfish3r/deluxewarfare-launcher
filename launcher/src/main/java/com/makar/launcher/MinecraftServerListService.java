package com.makar.launcher;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class MinecraftServerListService {
    private static final byte TAG_END = 0;
    private static final byte TAG_BYTE = 1;
    private static final byte TAG_SHORT = 2;
    private static final byte TAG_INT = 3;
    private static final byte TAG_LONG = 4;
    private static final byte TAG_FLOAT = 5;
    private static final byte TAG_DOUBLE = 6;
    private static final byte TAG_BYTE_ARRAY = 7;
    private static final byte TAG_STRING = 8;
    private static final byte TAG_LIST = 9;
    private static final byte TAG_COMPOUND = 10;
    private static final byte TAG_INT_ARRAY = 11;
    private static final byte TAG_LONG_ARRAY = 12;

    public void ensureServerEntry(
            Path gameDirectory,
            String serverName,
            String serverHost,
            int serverPort,
            List<String> legacyAddresses,
            Consumer<String> logConsumer
    ) {
        Path serversFile = gameDirectory.resolve("servers.dat");
        String targetAddress = serverHost + ":" + serverPort;
        NbtCompound root = Files.exists(serversFile) ? readRoot(serversFile) : new NbtCompound();

        NbtList servers = getOrCreateServersList(root);
        List<NbtTag> updatedServers = new ArrayList<>();
        boolean targetSeen = false;
        boolean migrated = false;

        for (NbtTag tag : servers.items) {
            if (!(tag instanceof NbtCompound server)) {
                updatedServers.add(tag);
                continue;
            }

            String address = getString(server, "ip");
            if (isTargetAddress(address, targetAddress) || isLegacyAddress(address, legacyAddresses)) {
                if (!targetSeen) {
                    putString(server, "name", serverName);
                    putString(server, "ip", targetAddress);
                    updatedServers.add(server);
                    targetSeen = true;
                }
                if (isLegacyAddress(address, legacyAddresses)) {
                    migrated = true;
                }
                continue;
            }

            updatedServers.add(server);
        }

        if (!targetSeen) {
            NbtCompound server = new NbtCompound();
            putString(server, "name", serverName);
            putString(server, "ip", targetAddress);
            updatedServers.add(server);
        }

        servers.items.clear();
        servers.items.addAll(updatedServers);

        writeRoot(serversFile, root);

        if (migrated) {
            logConsumer.accept("SERVER LIST | Migrated zuma.sos-al.net to " + targetAddress + ".");
        } else if (targetSeen) {
            logConsumer.accept("SERVER LIST | Already present " + targetAddress + ".");
        } else {
            logConsumer.accept("SERVER LIST | Added " + targetAddress + ".");
        }
    }

    private NbtCompound readRoot(Path serversFile) {
        try (InputStream fileInput = Files.newInputStream(serversFile);
                DataInputStream input = new DataInputStream(new GZIPInputStream(fileInput))) {
            byte type = input.readByte();
            if (type != TAG_COMPOUND) {
                throw new IllegalStateException("servers.dat root tag is not a compound.");
            }
            input.readUTF();
            return readCompoundPayload(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read servers.dat.", exception);
        }
    }

    private void writeRoot(Path serversFile, NbtCompound root) {
        try {
            Files.createDirectories(serversFile.getParent());
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create game directory.", exception);
        }

        try (OutputStream fileOutput = Files.newOutputStream(serversFile);
                DataOutputStream output = new DataOutputStream(new GZIPOutputStream(fileOutput))) {
            output.writeByte(TAG_COMPOUND);
            output.writeUTF("");
            writeCompoundPayload(output, root);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to write servers.dat.", exception);
        }
    }

    private NbtList getOrCreateServersList(NbtCompound root) {
        NbtTag existing = root.tags.get("servers");
        if (existing instanceof NbtList list && list.elementType == TAG_COMPOUND) {
            return list;
        }

        NbtList servers = new NbtList(TAG_COMPOUND, new ArrayList<>());
        root.tags.put("servers", servers);
        return servers;
    }

    private boolean isTargetAddress(String address, String targetAddress) {
        return normalizeAddress(address).equals(normalizeAddress(targetAddress));
    }

    private boolean isLegacyAddress(String address, List<String> legacyAddresses) {
        String normalizedAddress = normalizeAddress(address);
        for (String legacyAddress : legacyAddresses) {
            if (normalizedAddress.equals(normalizeAddress(legacyAddress))) {
                return true;
            }
        }
        return false;
    }

    private String normalizeAddress(String address) {
        return address == null ? "" : address.trim().toLowerCase();
    }

    private String getString(NbtCompound compound, String key) {
        NbtTag tag = compound.tags.get(key);
        return tag instanceof NbtString string ? string.value : "";
    }

    private void putString(NbtCompound compound, String key, String value) {
        compound.tags.put(key, new NbtString(value));
    }

    private NbtCompound readCompoundPayload(DataInputStream input) throws IOException {
        NbtCompound compound = new NbtCompound();
        while (true) {
            byte type = input.readByte();
            if (type == TAG_END) {
                return compound;
            }
            String name = input.readUTF();
            compound.tags.put(name, readPayload(input, type));
        }
    }

    private NbtTag readPayload(DataInputStream input, byte type) throws IOException {
        return switch (type) {
            case TAG_BYTE -> new NbtByte(input.readByte());
            case TAG_SHORT -> new NbtShort(input.readShort());
            case TAG_INT -> new NbtInt(input.readInt());
            case TAG_LONG -> new NbtLong(input.readLong());
            case TAG_FLOAT -> new NbtFloat(input.readFloat());
            case TAG_DOUBLE -> new NbtDouble(input.readDouble());
            case TAG_BYTE_ARRAY -> readByteArray(input);
            case TAG_STRING -> new NbtString(input.readUTF());
            case TAG_LIST -> readList(input);
            case TAG_COMPOUND -> readCompoundPayload(input);
            case TAG_INT_ARRAY -> readIntArray(input);
            case TAG_LONG_ARRAY -> readLongArray(input);
            default -> throw new IOException("Unsupported NBT tag type: " + type);
        };
    }

    private NbtByteArray readByteArray(DataInputStream input) throws IOException {
        int length = input.readInt();
        byte[] value = new byte[length];
        input.readFully(value);
        return new NbtByteArray(value);
    }

    private NbtList readList(DataInputStream input) throws IOException {
        byte elementType = input.readByte();
        int length = input.readInt();
        List<NbtTag> items = new ArrayList<>(Math.max(0, length));
        for (int index = 0; index < length; index++) {
            items.add(readPayload(input, elementType));
        }
        return new NbtList(elementType, items);
    }

    private NbtIntArray readIntArray(DataInputStream input) throws IOException {
        int length = input.readInt();
        int[] value = new int[length];
        for (int index = 0; index < length; index++) {
            value[index] = input.readInt();
        }
        return new NbtIntArray(value);
    }

    private NbtLongArray readLongArray(DataInputStream input) throws IOException {
        int length = input.readInt();
        long[] value = new long[length];
        for (int index = 0; index < length; index++) {
            value[index] = input.readLong();
        }
        return new NbtLongArray(value);
    }

    private void writeCompoundPayload(DataOutputStream output, NbtCompound compound) throws IOException {
        for (Map.Entry<String, NbtTag> entry : compound.tags.entrySet()) {
            output.writeByte(entry.getValue().type());
            output.writeUTF(entry.getKey());
            entry.getValue().writePayload(output);
        }
        output.writeByte(TAG_END);
    }

    private interface NbtTag {
        byte type();

        void writePayload(DataOutputStream output) throws IOException;
    }

    private static final class NbtCompound implements NbtTag {
        private final Map<String, NbtTag> tags = new LinkedHashMap<>();

        @Override
        public byte type() {
            return TAG_COMPOUND;
        }

        @Override
        public void writePayload(DataOutputStream output) throws IOException {
            for (Map.Entry<String, NbtTag> entry : tags.entrySet()) {
                output.writeByte(entry.getValue().type());
                output.writeUTF(entry.getKey());
                entry.getValue().writePayload(output);
            }
            output.writeByte(TAG_END);
        }
    }

    private record NbtByte(byte value) implements NbtTag {
        @Override
        public byte type() {
            return TAG_BYTE;
        }

        @Override
        public void writePayload(DataOutputStream output) throws IOException {
            output.writeByte(value);
        }
    }

    private record NbtShort(short value) implements NbtTag {
        @Override
        public byte type() {
            return TAG_SHORT;
        }

        @Override
        public void writePayload(DataOutputStream output) throws IOException {
            output.writeShort(value);
        }
    }

    private record NbtInt(int value) implements NbtTag {
        @Override
        public byte type() {
            return TAG_INT;
        }

        @Override
        public void writePayload(DataOutputStream output) throws IOException {
            output.writeInt(value);
        }
    }

    private record NbtLong(long value) implements NbtTag {
        @Override
        public byte type() {
            return TAG_LONG;
        }

        @Override
        public void writePayload(DataOutputStream output) throws IOException {
            output.writeLong(value);
        }
    }

    private record NbtFloat(float value) implements NbtTag {
        @Override
        public byte type() {
            return TAG_FLOAT;
        }

        @Override
        public void writePayload(DataOutputStream output) throws IOException {
            output.writeFloat(value);
        }
    }

    private record NbtDouble(double value) implements NbtTag {
        @Override
        public byte type() {
            return TAG_DOUBLE;
        }

        @Override
        public void writePayload(DataOutputStream output) throws IOException {
            output.writeDouble(value);
        }
    }

    private record NbtByteArray(byte[] value) implements NbtTag {
        @Override
        public byte type() {
            return TAG_BYTE_ARRAY;
        }

        @Override
        public void writePayload(DataOutputStream output) throws IOException {
            output.writeInt(value.length);
            output.write(value);
        }
    }

    private record NbtString(String value) implements NbtTag {
        @Override
        public byte type() {
            return TAG_STRING;
        }

        @Override
        public void writePayload(DataOutputStream output) throws IOException {
            output.writeUTF(value);
        }
    }

    private static final class NbtList implements NbtTag {
        private final byte elementType;
        private final List<NbtTag> items;

        private NbtList(byte elementType, List<NbtTag> items) {
            this.elementType = elementType;
            this.items = items;
        }

        @Override
        public byte type() {
            return TAG_LIST;
        }

        @Override
        public void writePayload(DataOutputStream output) throws IOException {
            output.writeByte(elementType);
            output.writeInt(items.size());
            for (NbtTag item : items) {
                item.writePayload(output);
            }
        }
    }

    private record NbtIntArray(int[] value) implements NbtTag {
        @Override
        public byte type() {
            return TAG_INT_ARRAY;
        }

        @Override
        public void writePayload(DataOutputStream output) throws IOException {
            output.writeInt(value.length);
            for (int item : value) {
                output.writeInt(item);
            }
        }
    }

    private record NbtLongArray(long[] value) implements NbtTag {
        @Override
        public byte type() {
            return TAG_LONG_ARRAY;
        }

        @Override
        public void writePayload(DataOutputStream output) throws IOException {
            output.writeInt(value.length);
            for (long item : value) {
                output.writeLong(item);
            }
        }
    }
}
