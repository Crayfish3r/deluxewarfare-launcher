package com.makar.launcher;

import java.awt.Desktop;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.geometry.Insets;
import javafx.animation.TranslateTransition;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

public final class LauncherApp extends Application {
    // 800x450 is too tight for the current auth, progress and shop UI; 960x540 keeps the layout clear without root scaling.
    private static final double WINDOW_WIDTH = 960;
    private static final double WINDOW_HEIGHT = 540;
    private static final double SIDE_PANEL_WIDTH = 312;
    private static final Pattern NICKNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final String EASYDONATE_SHOP_URL = "https://deluxewarfare.easydonate.ru/";
    private static final String PLAY_BUTTON_TEXT = "\u0418\u0413\u0420\u0410\u0422\u042C!";
    private static final String UPDATE_BUTTON_TEXT = "\u041E\u0411\u041D\u041E\u0412\u0418\u0422\u042C";

    private final LogService logService = new LogService();
    private final LauncherConfig config = LauncherConfig.load();
    private final ManifestService manifestService = new ManifestService();
    private final FileHashService fileHashService = new FileHashService();
    private final DownloadService downloadService = new DownloadService(fileHashService);
    private final ManagedFoldersIntegrityService managedFoldersIntegrityService = new ManagedFoldersIntegrityService();
    private final JavaRuntimeService javaRuntimeService = new JavaRuntimeService();
    private final MinecraftInstallService minecraftInstallService = new MinecraftInstallService();
    private final MinecraftLaunchService minecraftLaunchService = new MinecraftLaunchService();
    private final MinecraftServerListService minecraftServerListService = new MinecraftServerListService();
    private final ElyBySkinService elyBySkinService = new ElyBySkinService();
    private final DefaultGameSettingsService defaultGameSettingsService = new DefaultGameSettingsService();
    private final BackendAuthService backendAuthService = new BackendAuthService(config.getBackendUrl());
    private final TacticalAuthTokenService tacticalAuthTokenService = new TacticalAuthTokenService();
    private final LauncherSelfUpdateService launcherSelfUpdateService = new LauncherSelfUpdateService();
    private final Object updateOperationLock = new Object();

    private TextField nicknameField;
    private TextField loginCodeField;
    private Label discordStatusLabel;
    private Label discordUserLabel;
    private ProgressBar progressBar;
    private Label progressPercentLabel;
    private Label progressStatusLabel;
    private ProgressIndicator activityIndicator;
    private Button checkUpdatesButton;
    private Button playButton;
    private Button loginWithDiscordButton;
    private StackPane settingsOverlay;
    private PauseTransition loginCodeDebounce;
    private boolean loginCodeSubmitting;
    private boolean launcherBusy;
    private boolean launcherUpdateCheckCompleted;
    private boolean launcherUpdateCheckBlocked;
    private boolean discordSessionValidationPending;
    private boolean discordSessionValidationFailed;
    private LauncherUpdateInfo availableLauncherUpdate;
    private CompletableFuture<UpdateResult> activeUpdateFuture;
    private Process minecraftProcess;
    private RuntimeManagedFolderProtectionService runtimeManagedFolderProtectionService;

    @Override
    public void start(Stage stage) {
        LauncherPaths.createConfigDirectory();

        nicknameField = new TextField(config.getNickname());
        nicknameField.setId("nicknameField");
        nicknameField.setPromptText("Никнейм");
        nicknameField.getStyleClass().add("input-field");
        nicknameField.setPrefHeight(38);
        nicknameField.setMaxWidth(Double.MAX_VALUE);
        nicknameField.textProperty().addListener((observable, oldValue, newValue) -> updatePlayButtonState());

        boolean authUiEnabled = config.isDiscordAuthEnabled() || config.isBackendAuthEnabled();

        loginWithDiscordButton = createButton("Авторизация Discord", "discord-button", this::openDiscordLogin);
        loginWithDiscordButton.setId("loginWithDiscordButton");
        loginWithDiscordButton.setDisable(!config.isDiscordAuthEnabled());
        loginWithDiscordButton.setPrefHeight(38);
        loginWithDiscordButton.setMaxWidth(Double.MAX_VALUE);
        loginWithDiscordButton.setVisible(authUiEnabled);
        loginWithDiscordButton.setManaged(authUiEnabled);

        loginCodeField = new TextField();
        loginCodeField.setId("loginCodeField");
        loginCodeField.setPromptText("Введите код Discord");
        loginCodeField.getStyleClass().addAll("input-field", "discord-code-input");
        loginCodeField.setPrefHeight(38);
        loginCodeField.setMaxWidth(Double.MAX_VALUE);
        loginCodeField.setVisible(false);
        loginCodeField.setManaged(false);
        loginCodeDebounce = new PauseTransition(Duration.millis(500));
        loginCodeDebounce.setOnFinished(event -> exchangeLoginCode());
        loginCodeField.textProperty().addListener((observable, oldValue, newValue) -> {
            if (!loginCodeSubmitting && newValue.trim().length() >= 6) {
                loginCodeDebounce.playFromStart();
            }
        });

        discordStatusLabel = new Label();
        discordStatusLabel.setId("discordStatusLabel");
        discordStatusLabel.getStyleClass().add("discord-status-badge");
        discordStatusLabel.setAlignment(Pos.CENTER);
        discordStatusLabel.setPrefSize(16, 16);
        discordStatusLabel.setMinSize(16, 16);
        discordStatusLabel.setMaxSize(16, 16);
        discordStatusLabel.setVisible(authUiEnabled);
        discordStatusLabel.setManaged(authUiEnabled);

        discordUserLabel = new Label();
        discordUserLabel.getStyleClass().add("discord-user-label");
        discordUserLabel.setMaxWidth(Double.MAX_VALUE);
        discordUserLabel.setWrapText(true);

        checkUpdatesButton = createButton("Проверить", "utility-button", this::checkUpdates);
        checkUpdatesButton.setId("checkUpdatesButton");
        checkUpdatesButton.getStyleClass().add("update-button");
        checkUpdatesButton.setPrefHeight(34);
        checkUpdatesButton.setMaxWidth(Double.MAX_VALUE);

        playButton = createButton("ИГРАТЬ!", "play-button", this::handlePrimaryAction);
        playButton.setId("playButton");
        playButton.setPrefHeight(58);
        playButton.setMaxWidth(Double.MAX_VALUE);

        progressBar = new ProgressBar(0);
        progressBar.setId("progressBar");
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setPrefHeight(10);

        progressPercentLabel = new Label("");
        progressPercentLabel.getStyleClass().add("progress-percent-label");
        progressPercentLabel.setPrefSize(42, 22);
        progressPercentLabel.setAlignment(Pos.CENTER_RIGHT);
        progressPercentLabel.setVisible(false);
        progressPercentLabel.setManaged(false);

        progressStatusLabel = new Label("Готово");
        progressStatusLabel.getStyleClass().add("progress-status-label");
        progressStatusLabel.setMaxWidth(Double.MAX_VALUE);
        progressStatusLabel.setWrapText(true);
        progressStatusLabel.setTooltip(new Tooltip("Готово"));

        activityIndicator = new ProgressIndicator();
        activityIndicator.getStyleClass().add("activity-indicator");
        activityIndicator.setPrefSize(18, 18);
        activityIndicator.setMinSize(18, 18);
        activityIndicator.setMaxSize(18, 18);
        activityIndicator.setVisible(false);
        activityIndicator.setManaged(false);

        TextArea logArea = new TextArea();
        logArea.setId("logArea");
        logArea.getStyleClass().add("log-text-area");
        logArea.setEditable(false);
        logArea.setWrapText(false);
        logArea.textProperty().bind(logService.textProperty());
        logService.textProperty().addListener((observable, oldValue, newValue) ->
                logArea.positionCaret(newValue.length()));
        logArea.setPrefSize(500, 360);

        StackPane logsOverlay = createLogsOverlay(logArea);
        settingsOverlay = createSettingsOverlay();
        Pane content = createContentPane(authUiEnabled, logsOverlay);
        Pane background = createBackground();
        StackPane root = new StackPane(background, content, settingsOverlay, logsOverlay);
        root.getStyleClass().add("background-root");
        root.setMinSize(WINDOW_WIDTH, WINDOW_HEIGHT);
        root.setPrefSize(WINDOW_WIDTH, WINDOW_HEIGHT);
        root.setMaxSize(WINDOW_WIDTH, WINDOW_HEIGHT);

        stage.initStyle(StageStyle.DECORATED);
        stage.setTitle("Deluxe Warfare Launcher");
        stage.setResizable(false);
        stage.getIcons().add(new Image(resourceUrl("/images/ui/launcher_icon.png")));
        Scene scene = new Scene(root, WINDOW_WIDTH, WINDOW_HEIGHT);
        scene.getStylesheets().add(resourceUrl("/styles/launcher.css"));
        stage.setScene(scene);
        stage.setOnCloseRequest(event -> {
            terminateMinecraftBecauseLauncherClosed();
        });
        stage.show();
        updateDiscordUi();
        updatePlayButtonState();
        validateStoredDiscordSessionOnStartup();

        logService.info("Settings directory: " + LauncherPaths.getConfigDirectory());
        logService.info("Game directory: " + LauncherPaths.getGameDirectory(config));
        logService.info("Manifest URL: " + config.getManifestUrl());
        logService.info("Server: " + getServerAddressText());
        logService.info("Auto join server enabled: " + config.isAutoJoinServer());
        logService.info("Backend URL: " + config.getBackendUrl());
        logService.info("Discord auth enabled: " + config.isDiscordAuthEnabled());
        logService.info("Backend auth enabled: " + config.isBackendAuthEnabled());
        logService.info("Skin system enabled: " + config.isSkinSystemEnabled());
        logService.info("Launcher version: " + launcherSelfUpdateService.getCurrentVersion());
        launcherSelfUpdateService.getCurrentVersionWarning().ifPresent(logService::warn);
        logService.info("Launcher update URL: " + config.getLauncherUpdateUrl());
        logService.info("Donation products URL: " + config.getDonationProductsUrl());
        logService.info("DonationAlerts URL: " + config.getDonationAlertsUrl());
        logService.info("Minecraft memory: " + config.getMemoryGb() + " GB");
        logService.info("Launcher is ready.");
        checkLauncherUpdateOnStartup();
    }

    @Override
    public void stop() {
        terminateMinecraftBecauseLauncherClosed();
        stopRuntimeManagedFolderProtection();
        config.setNickname(nicknameField.getText().trim());
        config.save();
    }

    private Pane createBackground() {
        double rightAreaWidth = WINDOW_WIDTH - SIDE_PANEL_WIDTH;
        Image backgroundImage = new Image(resourceUrl("/images/ui/launcher_background.png"));
        ImageView background = new ImageView(backgroundImage);
        background.setPreserveRatio(true);

        double imageWidth = backgroundImage.getWidth();
        double imageHeight = backgroundImage.getHeight();
        double imageAspect = imageWidth / imageHeight;
        double rightAreaAspect = rightAreaWidth / WINDOW_HEIGHT;
        if (imageAspect >= rightAreaAspect) {
            background.setFitHeight(WINDOW_HEIGHT);
            background.setX((rightAreaWidth - WINDOW_HEIGHT * imageAspect) / 2.0);
        } else {
            background.setFitWidth(rightAreaWidth);
            background.setY((WINDOW_HEIGHT - rightAreaWidth / imageAspect) / 2.0);
        }

        Pane rightBackground = new Pane(background);
        rightBackground.setManaged(false);
        rightBackground.setLayoutX(SIDE_PANEL_WIDTH);
        rightBackground.setLayoutY(0);
        rightBackground.setPrefSize(rightAreaWidth, WINDOW_HEIGHT);
        rightBackground.setMinSize(rightAreaWidth, WINDOW_HEIGHT);
        rightBackground.setMaxSize(rightAreaWidth, WINDOW_HEIGHT);
        rightBackground.setClip(new Rectangle(rightAreaWidth, WINDOW_HEIGHT));
        return rightBackground;
    }

    private Pane createContentPane(boolean authUiEnabled, StackPane logsOverlay) {
        Pane content = new Pane();
        content.setPrefSize(WINDOW_WIDTH, WINDOW_HEIGHT);

        Label version = new Label("v" + launcherSelfUpdateService.getCurrentVersion());
        version.getStyleClass().add("version-label");

        Button logsButton = createButton("Логи", "logs-button", () -> showOverlay(logsOverlay));
        logsButton.setPrefSize(72, 32);

        Button settingsButton = createButton("Настройки", "utility-button", this::openSettingsDialog);
        settingsButton.getStyleClass().add("settings-button");
        settingsButton.setPrefSize(106, 32);

        Button donationButton = createButton("МАГАЗИН", "utility-button",
                () -> openExternalUrl(EASYDONATE_SHOP_URL));
        donationButton.getStyleClass().add("donation-button");
        donationButton.setPrefHeight(34);
        donationButton.setMaxWidth(Double.MAX_VALUE);

        Label authTitle = new Label("Авторизация / Вход");
        authTitle.getStyleClass().add("section-title");

        HBox discordInputRow = new HBox(8, loginWithDiscordButton, loginCodeField, discordStatusLabel);
        discordInputRow.getStyleClass().add("discord-status-row");
        discordInputRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(loginWithDiscordButton, Priority.ALWAYS);
        HBox.setHgrow(loginCodeField, Priority.ALWAYS);

        HBox actionRow = new HBox(10, checkUpdatesButton, donationButton);
        actionRow.setAlignment(Pos.CENTER);
        HBox.setHgrow(checkUpdatesButton, Priority.ALWAYS);
        HBox.setHgrow(donationButton, Priority.ALWAYS);

        HBox progressStatusRow = new HBox(8, activityIndicator, progressStatusLabel, progressPercentLabel);
        progressStatusRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(progressStatusLabel, Priority.ALWAYS);

        VBox progressContainer = new VBox(9, progressStatusRow, progressBar);
        progressContainer.getStyleClass().add("progress-container");
        progressContainer.setMaxWidth(Double.MAX_VALUE);

        Region sideSpacer = new Region();
        VBox.setVgrow(sideSpacer, Priority.ALWAYS);

        VBox sidePanel = new VBox(14,
                authTitle,
                nicknameField,
                discordInputRow,
                discordUserLabel,
                playButton,
                actionRow,
                progressContainer,
                sideSpacer,
                version);
        sidePanel.getStyleClass().add("side-panel");
        sidePanel.setLayoutX(0);
        sidePanel.setLayoutY(0);
        sidePanel.setPrefSize(SIDE_PANEL_WIDTH, WINDOW_HEIGHT);
        sidePanel.setMaxSize(SIDE_PANEL_WIDTH, WINDOW_HEIGHT);

        Region topSpacer = new Region();
        HBox.setHgrow(topSpacer, Priority.ALWAYS);
        HBox topBar = new HBox(12, topSpacer, settingsButton, logsButton);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setLayoutX(SIDE_PANEL_WIDTH + 28);
        topBar.setLayoutY(24);
        topBar.setPrefWidth(WINDOW_WIDTH - SIDE_PANEL_WIDTH - 56);

        content.getChildren().addAll(
                sidePanel,
                topBar
        );

        loginWithDiscordButton.setVisible(authUiEnabled);
        loginWithDiscordButton.setManaged(authUiEnabled);
        return content;
    }

    private void openSettingsDialog() {
        if (settingsOverlay != null) {
            showOverlay(settingsOverlay);
        }
    }

    private StackPane createSettingsOverlay() {
        int maxMemoryGb = getMaxConfigurableMemoryGb();
        int currentMemoryGb = Math.min(maxMemoryGb, Math.max(4, config.getMemoryGb()));

        StackPane overlay = new StackPane();
        overlay.getStyleClass().add("overlay-backdrop");
        overlay.setPrefSize(WINDOW_WIDTH, WINDOW_HEIGHT);
        overlay.setMaxSize(WINDOW_WIDTH, WINDOW_HEIGHT);
        overlay.setVisible(false);
        overlay.setManaged(false);
        overlay.setOpacity(0.0);

        Label title = new Label("Настройки");
        title.getStyleClass().add("overlay-title");

        Button closeButton = createButton("×", "overlay-close-button", () -> hideOverlay(overlay));

        HBox header = new HBox(12);
        header.getStyleClass().add("overlay-header");
        header.setAlignment(Pos.CENTER_LEFT);
        header.getChildren().addAll(title, closeButton);
        HBox.setHgrow(title, Priority.ALWAYS);

        Slider memorySlider = new Slider(4, maxMemoryGb, currentMemoryGb);
        memorySlider.setShowTickLabels(true);
        memorySlider.setShowTickMarks(true);
        memorySlider.setMajorTickUnit(1);
        memorySlider.setMinorTickCount(0);
        memorySlider.setBlockIncrement(1);
        memorySlider.setSnapToTicks(true);

        Label selectedMemoryLabel = new Label(currentMemoryGb + " GB");
        selectedMemoryLabel.getStyleClass().add("settings-value-label");
        memorySlider.valueProperty().addListener((observable, oldValue, newValue) ->
                selectedMemoryLabel.setText(Math.round(newValue.doubleValue()) + " GB"));

        Label description = new Label("Оперативная память для Minecraft. Минимум: 4 GB. Максимум зависит от RAM компьютера.");
        description.getStyleClass().add("settings-description");
        description.setWrapText(true);

        Button cancelButton = createButton("Отмена", "utility-button", () -> hideOverlay(overlay));
        cancelButton.setPrefWidth(110);

        Button saveButton = createButton("Сохранить", "utility-button", () -> {
            int selectedMemoryGb = (int) Math.round(memorySlider.getValue());
            config.setMemoryGb(Math.min(maxMemoryGb, Math.max(4, selectedMemoryGb)));
            config.save();
            logService.info("Minecraft memory changed to " + config.getMemoryGb() + " GB.");
            hideOverlay(overlay);
        });
        saveButton.setPrefWidth(130);

        HBox actions = new HBox(10, cancelButton, saveButton);
        actions.setAlignment(Pos.CENTER_RIGHT);

        VBox panel = new VBox(14, header, description, memorySlider, selectedMemoryLabel, actions);
        panel.getStyleClass().addAll("overlay-panel", "settings-content");
        panel.setPadding(new Insets(18));
        panel.setPrefSize(430, 260);
        panel.setMaxSize(430, 260);
        overlay.getChildren().add(panel);
        return overlay;
    }

    private int getMaxConfigurableMemoryGb() {
        try {
            java.lang.management.OperatingSystemMXBean systemBean =
                    ManagementFactory.getOperatingSystemMXBean();
            if (systemBean instanceof com.sun.management.OperatingSystemMXBean extendedBean) {
                long totalMemoryBytes = extendedBean.getTotalMemorySize();
                long totalMemoryGb = totalMemoryBytes / 1024 / 1024 / 1024;
                return (int) Math.max(4, totalMemoryGb - 2);
            }
        } catch (RuntimeException ignored) {
            return 4;
        }

        return 4;
    }

    private StackPane createLogsOverlay(TextArea logArea) {
        StackPane overlay = new StackPane();
        overlay.getStyleClass().add("overlay-backdrop");
        overlay.setPrefSize(WINDOW_WIDTH, WINDOW_HEIGHT);
        overlay.setMaxSize(WINDOW_WIDTH, WINDOW_HEIGHT);
        overlay.setVisible(false);
        overlay.setManaged(false);
        overlay.setOpacity(0.0);

        Label title = new Label("Логи лаунчера");
        title.getStyleClass().add("overlay-title");

        Button clearButton = createButton("Очистить", "utility-button", logService::clearUiLog);
        clearButton.getStyleClass().add("overlay-action-button");
        Button openFolderButton = createButton("Открыть папку", "utility-button", this::openLogFolder);
        openFolderButton.getStyleClass().add("overlay-action-button");
        Button closeButton = createButton("×", "overlay-close-button", () -> hideOverlay(overlay));

        HBox header = new HBox(10);
        header.getStyleClass().add("overlay-header");
        header.setAlignment(Pos.CENTER_LEFT);
        header.getChildren().addAll(title, clearButton, openFolderButton, closeButton);
        HBox.setHgrow(title, Priority.ALWAYS);

        VBox panel = new VBox(12, header, logArea);
        panel.getStyleClass().addAll("overlay-panel", "logs-overlay");
        panel.setPrefSize(660, 430);
        panel.setMaxSize(660, 430);
        VBox.setVgrow(logArea, Priority.ALWAYS);
        overlay.getChildren().add(panel);
        return overlay;
    }

    private Button createButton(String text, String styleClass, Runnable action) {
        Button button = new Button(text);
        if (styleClass != null && !styleClass.isBlank()) {
            button.getStyleClass().add(styleClass);
        }
        button.setFocusTraversable(false);
        if (action != null) {
            button.setOnAction(event -> action.run());
        }
        return button;
    }

    private void showOverlay(StackPane overlay) {
        Node panel = getOverlayPanel(overlay);
        overlay.setManaged(true);
        overlay.setVisible(true);
        overlay.toFront();
        overlay.setOpacity(0.0);
        panel.setTranslateY(-12);

        FadeTransition fade = new FadeTransition(Duration.millis(150), overlay);
        fade.setFromValue(0.0);
        fade.setToValue(1.0);

        TranslateTransition slide = new TranslateTransition(Duration.millis(150), panel);
        slide.setFromY(-12);
        slide.setToY(0);

        new ParallelTransition(fade, slide).play();
    }

    private void hideOverlay(StackPane overlay) {
        Node panel = getOverlayPanel(overlay);

        FadeTransition fade = new FadeTransition(Duration.millis(140), overlay);
        fade.setFromValue(overlay.getOpacity());
        fade.setToValue(0.0);

        TranslateTransition slide = new TranslateTransition(Duration.millis(140), panel);
        slide.setFromY(panel.getTranslateY());
        slide.setToY(-12);

        ParallelTransition transition = new ParallelTransition(fade, slide);
        transition.setOnFinished(event -> {
            overlay.setVisible(false);
            overlay.setManaged(false);
            panel.setTranslateY(0);
        });
        transition.play();
    }

    private Node getOverlayPanel(StackPane overlay) {
        if (overlay.getChildren().isEmpty()) {
            return overlay;
        }
        return overlay.getChildren().get(overlay.getChildren().size() - 1);
    }

    private String resourceUrl(String path) {
        URL resource = LauncherApp.class.getResource(path);
        if (resource == null) {
            throw new IllegalStateException("Missing launcher resource: " + path);
        }

        return resource.toExternalForm();
    }

    private void openLogFolder() {
        Path logFile = logService.getLogFile();
        Path logDirectory = logFile.getParent();
        if (logDirectory == null) {
            logService.warn("Папка логов недоступна.");
            return;
        }

        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            logService.warn("Открытие папки логов недоступно в этой системе: " + logDirectory);
            return;
        }

        try {
            Desktop.getDesktop().open(logDirectory.toFile());
        } catch (IOException exception) {
            logService.warn("Не удалось открыть папку логов: " + exception.getMessage());
        }
    }

    private void openExternalUrl(String url) {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            logService.warn("System browser is not available. Open manually: " + url);
            return;
        }

        try {
            Desktop.getDesktop().browse(URI.create(url));
        } catch (IOException exception) {
            logService.warn("Unable to open browser: " + exception.getMessage());
        }
    }

    private void openDiscordLogin() {
        URI loginUri = backendAuthService.getDiscordLoginUri();
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            logService.warn("System browser is not available. Open manually: " + loginUri);
            return;
        }

        try {
            Desktop.getDesktop().browse(loginUri);
            logService.info("Opened Discord login in browser.");
            discordSessionValidationPending = false;
            discordSessionValidationFailed = false;
            showDiscordCodeInput();
            updateDiscordUi();
            updatePlayButtonState();
        } catch (IOException exception) {
            logService.warn("Unable to open browser: " + exception.getMessage());
        }
    }

    private void exchangeLoginCode() {
        String code = loginCodeField.getText().trim();
        if (code.length() < 6 || loginCodeSubmitting) {
            return;
        }

        loginCodeSubmitting = true;
        loginCodeField.setDisable(true);
        discordSessionValidationPending = false;
        discordSessionValidationFailed = false;
        updateDiscordUi();
        updatePlayButtonState();
        logService.info("Exchanging launcher login code...");

        CompletableFuture
                .supplyAsync(() -> backendAuthService.exchangeLauncherCode(code))
                .whenComplete((response, throwable) -> updateUi(() -> {
                    loginCodeSubmitting = false;
                    loginCodeField.setDisable(false);

                    if (throwable != null) {
                        logService.warn("Login failed: " + getErrorMessage(throwable));
                        updateDiscordUi();
                        return;
                    }

                    config.setLauncherSessionToken(response.getLauncherSessionToken());
                    config.setDiscordUsername(response.getUser().getUsername());
                    config.setDiscordUserId(getDiscordUserId(response.getUser()));
                    discordSessionValidationPending = false;
                    discordSessionValidationFailed = false;
                    config.save();
                    loginCodeField.clear();
                    hideDiscordCodeInput();
                    updateDiscordUi();
                    updatePlayButtonState();
                    logService.info("Logged in as Discord user: " + response.getUser().getUsername());
                }));
    }


    private void checkLauncherUpdateOnStartup() {
        if (launcherSelfUpdateService.isSelfUpdateCheckDisabledForDev()) {
            launcherUpdateCheckCompleted = true;
            launcherUpdateCheckBlocked = false;
            availableLauncherUpdate = null;
            updatePlayButtonState();
            setProgressStatus("Проверка обновления лаунчера отключена");
            logService.warn("Проверка обновления лаунчера отключена для dev-запуска.");
            return;
        }

        if (config.getLauncherUpdateUrl().isBlank()) {
            launcherUpdateCheckCompleted = true;
            launcherUpdateCheckBlocked = true;
            availableLauncherUpdate = null;
            updatePlayButtonState();
            setProgressStatus("Ошибка проверки обновления лаунчера");
            logService.warn("URL проверки обновления лаунчера не задан. Запуск заблокирован до исправления конфигурации.");
            return;
        }

        CompletableFuture
                .supplyAsync(() -> launcherSelfUpdateService.checkForUpdate(config.getLauncherUpdateUrl()))
                .whenComplete((updateInfo, throwable) -> updateUi(() -> {
                    launcherUpdateCheckCompleted = true;
                    if (throwable != null) {
                        availableLauncherUpdate = null;
                        if (isTemporaryLauncherUpdateFailure(throwable)) {
                            launcherUpdateCheckBlocked = false;
                            updatePlayButtonState();
                            logService.warn("Временная ошибка проверки обновления лаунчера. Запуск не заблокирован: "
                                    + getErrorMessage(throwable));
                        } else {
                            launcherUpdateCheckBlocked = true;
                            updatePlayButtonState();
                            setProgressStatus("Metadata обновления лаунчера повреждена");
                            logService.warn("Metadata обновления лаунчера повреждена или некорректна. "
                                    + "Запуск заблокирован, потому что обязательность обновления нельзя проверить: "
                                    + getErrorMessage(throwable));
                        }
                        return;
                    }

                    launcherUpdateCheckBlocked = false;
                    if (updateInfo.isEmpty()) {
                        availableLauncherUpdate = null;
                        updatePlayButtonState();
                        logService.info("Launcher is up to date.");
                        return;
                    }

                    availableLauncherUpdate = updateInfo.get();

                    logService.info("Launcher update available: "
                            + launcherSelfUpdateService.getCurrentVersion()
                            + " -> "
                            + availableLauncherUpdate.getVersion());

                    if (!availableLauncherUpdate.getNotes().isBlank()) {
                        logService.info("Launcher update notes: " + availableLauncherUpdate.getNotes());
                    }

                    if (isMandatoryLauncherUpdateAvailable()) {
                        checkUpdatesButton.setDisable(true);
                        logService.warn("Доступно обязательное обновление лаунчера. Установите его перед Play.");
                    } else {
                        logService.warn("Доступно необязательное обновление лаунчера. Запуск не заблокирован.");
                    }
                    updatePlayButtonState();
                }));
    }

    private void handlePrimaryAction() {
        if (isMandatoryLauncherUpdateAvailable()) {
            downloadAndStartLauncherUpdate();
            return;
        }

        play();
    }

    private void downloadAndStartLauncherUpdate() {
        if (availableLauncherUpdate == null) {
            logService.info("No launcher update available.");
            return;
        }
        if (launcherBusy || isUpdateOperationRunning()) {
            logService.warn("Обновление уже выполняется. Дождитесь завершения текущей операции.");
            return;
        }

        launcherBusy = true;
        updatePlayButtonState();
        checkUpdatesButton.setDisable(true);
        setProgressState(0.0, "Скачивание обновления лаунчера...");

        logService.info("Downloading launcher update " + availableLauncherUpdate.getVersion() + "...");

        CompletableFuture
                .supplyAsync(() -> launcherSelfUpdateService.downloadInstaller(
                        availableLauncherUpdate,
                        message -> updateUi(() -> logService.info(message))))
                .whenComplete((installerPath, throwable) -> updateUi(() -> {
                    if (throwable != null) {
                        launcherBusy = false;
                        updatePlayButtonState();
                        checkUpdatesButton.setDisable(isMandatoryLauncherUpdateAvailable());
                        setProgressState(0.0, "Ошибка обновления лаунчера");
                        logService.warn("Launcher update failed: " + getErrorMessage(throwable));
                        return;
                    }

                    setProgressState(1.0, "Установщик лаунчера скачан");
                    logService.info("Launcher installer downloaded: " + installerPath);
                    logService.info("Starting installer. The launcher will close.");
                    try {
                        launcherSelfUpdateService.startInstaller(installerPath);
                    } catch (RuntimeException exception) {
                        launcherBusy = false;
                        updatePlayButtonState();
                        checkUpdatesButton.setDisable(isMandatoryLauncherUpdateAvailable());
                        setProgressState(0.0, "Не удалось запустить установщик лаунчера");
                        logService.warn("Не удалось запустить установщик лаунчера: " + getErrorMessage(exception));
                        return;
                    }
                    Platform.exit();
                }));
    }

    private void checkUpdates() {
        if (launcherBusy || isUpdateOperationRunning()) {
            logService.warn("Обновление уже выполняется. Дождитесь завершения текущей операции.");
            return;
        }

        setProgressState(0.0, "Проверка обновлений...");
        launcherBusy = true;
        updatePlayButtonState();
        checkUpdatesButton.setDisable(true);
        logService.info("Checking updates...");

        runUpdate()
                .whenComplete((ignored, throwable) -> updateUi(() -> {
                    launcherBusy = false;
                    updatePlayButtonState();
                    checkUpdatesButton.setDisable(isMandatoryLauncherUpdateAvailable());

                    if (throwable != null) {
                        setProgressState(0.0, "Ошибка проверки обновлений");
                        logService.warn("FAILED | Update check failed: " + getErrorMessage(throwable));
                        return;
                    }

                    setProgressState(1.0, "Обновления проверены");
                    logService.info("Update check completed.");
                }));
    }

    private void play() {
        if (!launcherUpdateCheckCompleted) {
            logService.warn("Проверка обновления лаунчера еще выполняется. Подождите.");
            return;
        }

        if (launcherUpdateCheckBlocked) {
            logService.warn("Запуск заблокирован: metadata обновления лаунчера повреждена или некорректна.");
            return;
        }

        if (isMandatoryLauncherUpdateAvailable()) {
            logService.warn("Доступно обязательное обновление лаунчера. Установите его перед Play.");
            return;
        }

        if (launcherBusy || isUpdateOperationRunning()) {
            logService.warn("Обновление уже выполняется. Дождитесь завершения текущей операции.");
            return;
        }

        String nickname = nicknameField.getText().trim();
        if (!isValidNickname(nickname)) {
            logService.warn("Nickname must be 3-16 characters and contain only A-Z, a-z, 0-9 or _.");
            return;
        }

        if (config.isDiscordAuthEnabled() && config.isBackendAuthEnabled() && config.getLauncherSessionToken().isBlank()) {
            logService.warn("Login with Discord before pressing Play, or disable backendAuthEnabled in launcher config.");
            return;
        }

        config.setNickname(nickname);
        config.save();
        setProgressState(0.0, "Подготовка запуска...");
        launcherBusy = true;
        updatePlayButtonState();
        checkUpdatesButton.setDisable(true);
        logService.info("Updating before launch...");

        runUpdate()
                .thenAccept(updateResult -> {
                    Path gameDirectory = LauncherPaths.createGameDirectory(config);
                    updateUi(() -> setProgressState(0.95, "Проверка папок клиента..."));
                    validateManagedFoldersBeforeLaunch(updateResult.manifest(), gameDirectory, nickname);
                    ensureMinecraftServerList(gameDirectory);
                    defaultGameSettingsService.applyOnce(
                            gameDirectory,
                            message -> updateUi(() -> logService.info(message))
                    );
                    prepareSkinSystem(gameDirectory);

                    if (config.isBackendAuthEnabled()) {
                        prepareBackendAuthToken(gameDirectory, nickname);
                    }

                    MinecraftLaunchOptions options = new MinecraftLaunchOptions(
                            updateResult.javaRuntimeInfo().getExecutable(),
                            gameDirectory,
                            nickname,
                            updateResult.manifest().getMinecraftVersion(),
                            updateResult.manifest().getForgeVersion(),
                            config.getServerHost(),
                            config.getServerPort(),
                            config.isAutoJoinServer(),
                            null,
                            "",
                            config.getMemoryGb()
                    );

                    updateUi(() -> {
                        setProgressState(1.0, "Запуск Minecraft...");
                        logService.info("Starting Minecraft Forge " + options.getForgeVersionName() + "...");
                        logService.info("Minecraft memory: " + options.getMemoryGb() + " GB.");
                    });
                    if (options.shouldAutoJoinServer()) {
                        updateUi(() -> logService.info("Joining server: " + options.getServerAddress()));
                    } else if (options.hasServerAddress()) {
                        updateUi(() -> logService.info("Auto join server is disabled. Starting Minecraft menu."));
                    }
                    Process process = minecraftLaunchService.startMinecraft(options, line -> updateUi(() -> logService.info(line)));
                    minecraftProcess = process;
                    startRuntimeManagedFolderProtection(process, updateResult.manifest(), gameDirectory, nickname);
                    process.onExit().thenAccept(exitedProcess -> updateUi(() -> {
                        stopRuntimeManagedFolderProtection();
                        minecraftProcess = null;
                        launcherBusy = false;
                        updatePlayButtonState();
                        checkUpdatesButton.setDisable(isMandatoryLauncherUpdateAvailable());
                        setProgressState(1.0, "Minecraft закрыт");
                        logService.info("Minecraft exited with code " + exitedProcess.exitValue() + ".");
                    }));
                })
                .whenComplete((ignored, throwable) -> {
                    if (throwable == null) {
                        return;
                    }

                    updateUi(() -> {
                        clearExpiredLauncherSessionIfNeeded(throwable);
                        launcherBusy = false;
                        updatePlayButtonState();
                        checkUpdatesButton.setDisable(isMandatoryLauncherUpdateAvailable());
                        setProgressState(0.0, "Ошибка запуска");
                        logService.warn("FAILED | Launch failed: " + getErrorMessage(throwable));
                    });
                });
    }

    private void startRuntimeManagedFolderProtection(
            Process process,
            LauncherManifest manifest,
            Path gameDirectory,
            String nickname
    ) {
        if (!config.isRuntimeManagedFolderProtectionEnabled()) {
            updateUi(() -> logService.warn("Runtime managed folder protection is disabled."));
            return;
        }

        stopRuntimeManagedFolderProtection();
        runtimeManagedFolderProtectionService = new RuntimeManagedFolderProtectionService(
                managedFoldersIntegrityService,
                fileHashService,
                manifest,
                gameDirectory,
                process,
                config.isTerminateGameOnRuntimeForbiddenFile(),
                config.getRuntimeManagedFolderScanIntervalSeconds(),
                message -> updateUi(() -> logService.warn(message)),
                detectedFiles -> {
                    var unknownFiles = detectedFiles.stream()
                            .map(RuntimeManagedFolderProtectionService.DetectedFile::path)
                            .toList();
                    BackendAuthService.ModerationReportResponse response = backendAuthService.reportIntegrityViolation(
                            config.getLauncherSessionToken(),
                            nickname,
                            manifest.getModpackVersion(),
                            launcherSelfUpdateService.getCurrentVersion(),
                            "runtime",
                            unknownFiles,
                            detectedFiles
                    );
                    if (response.isOk()) {
                        updateUi(() -> logService.info("Runtime moderation alert sent."));
                    }
                }
        );
        runtimeManagedFolderProtectionService.start();
    }

    private void stopRuntimeManagedFolderProtection() {
        if (runtimeManagedFolderProtectionService == null) {
            return;
        }

        runtimeManagedFolderProtectionService.stop();
        runtimeManagedFolderProtectionService = null;
    }

    private void terminateMinecraftBecauseLauncherClosed() {
        if (minecraftProcess == null || !minecraftProcess.isAlive()) {
            return;
        }

        logService.warn("Launcher is closing. Minecraft will be terminated to keep runtime protection strict.");
        minecraftProcess.destroy();
        if (minecraftProcess.isAlive()) {
            minecraftProcess.destroyForcibly();
        }
    }

    private void prepareBackendAuthToken(Path gameDirectory, String nickname) {
        String launcherSessionToken = config.getLauncherSessionToken();
        if (launcherSessionToken.isBlank()) {
            throw new IllegalStateException("Login with Discord before pressing Play.");
        }

        updateUi(() -> logService.info("Binding Minecraft nickname to Discord session..."));
        BackendAuthService.BindNicknameResponse bindResponse = backendAuthService.bindNickname(launcherSessionToken, nickname);
        if (!bindResponse.isOk()) {
            throw new IllegalStateException("Backend rejected Minecraft nickname binding.");
        }

        updateUi(() -> {
            String bindStatus = bindResponse.isAlreadyBound() ? "already bound" : "bound";
            logService.info("Minecraft nickname " + bindStatus + ": " + bindResponse.getProfile().getNickname());
        });

        updateUi(() -> logService.info("Requesting game token..."));
        BackendAuthService.GameTokenResponse tokenResponse = backendAuthService.requestGameToken(
                launcherSessionToken,
                nickname
        );
        if (!tokenResponse.isOk() || tokenResponse.getToken().isBlank()) {
            throw new IllegalStateException("Backend did not return a game token.");
        }

        Path tokenPath = tacticalAuthTokenService.writeToken(
                gameDirectory,
                tokenResponse.getNickname(),
                tokenResponse.getToken(),
                backendAuthService.getBackendUrl(),
                tokenResponse.getExpiresAt()
        );

        updateUi(() -> logService.info("Wrote tactical auth token: " + tokenPath));
    }

    private void prepareSkinSystem(Path gameDirectory) {
        if (!config.isSkinSystemEnabled()) {
            updateUi(() -> logService.info("Ely.by skin system is disabled in launcher config."));
            return;
        }

        updateUi(() -> logService.info("Preparing Ely.by skin system..."));
        elyBySkinService.prepare(
                gameDirectory,
                message -> updateUi(() -> logService.info(message))
        );
    }

    private void ensureMinecraftServerList(Path gameDirectory) {
        try {
            updateUi(() -> logService.info("Ensuring Minecraft server list contains Deluxe Warfare..."));
            minecraftServerListService.ensureServerEntry(
                    gameDirectory,
                    "Deluxe Warfare",
                    config.getServerHost(),
                    config.getServerPort(),
                    List.of("zuma.sos-al.net", "zuma.sos-al.net:25565"),
                    message -> updateUi(() -> logService.info(message))
            );
        } catch (RuntimeException exception) {
            updateUi(() -> logService.warn("Could not update Minecraft server list. Auto-join will still be used. "
                    + exception.getMessage()));
        }
    }

    private CompletableFuture<UpdateResult> runUpdate() {
        CompletableFuture<UpdateResult> updateFuture;
        synchronized (updateOperationLock) {
            if (activeUpdateFuture != null && !activeUpdateFuture.isDone()) {
                CompletableFuture<UpdateResult> rejectedFuture = new CompletableFuture<>();
                rejectedFuture.completeExceptionally(new IllegalStateException(
                        "Обновление уже выполняется. Дождитесь завершения текущей операции."));
                return rejectedFuture;
            }

            updateFuture = CompletableFuture.supplyAsync(() -> {
            Path gameDirectory = LauncherPaths.createGameDirectory(config);
            updateUi(() -> setProgressState(0.05, "Проверка Java..."));
            JavaRuntimeService.JavaRuntimeInfo javaRuntimeInfo = javaRuntimeService.checkJavaRuntime(config.getJavaPath());
            updateUi(() -> logJavaRuntime(javaRuntimeInfo));

            updateUi(() -> setProgressState(0.10, "Загрузка манифеста..."));
            LauncherManifest manifest = manifestService.downloadManifest(
                    config.getManifestUrl(),
                    config.getYandexDiskPublicUrl(),
                    message -> updateUi(() -> logService.warn(message)));
            updateUi(() -> {
                logManifest(manifest);
                setProgressState(0.18, "Манифест загружен");
            });

            updateUi(() -> setProgressState(0.22, "Подготовка Minecraft и Forge..."));
            MinecraftInstallOptions installOptions = new MinecraftInstallOptions(
                    javaRuntimeInfo.getExecutable(),
                    gameDirectory,
                    manifest.getMinecraftVersion(),
                    manifest.getForgeVersion()
            );
            minecraftInstallService.ensureInstalled(
                    installOptions,
                    message -> updateUi(() -> logService.info(message)),
                    (progress, status) -> updateUi(() -> setProgressState(0.22 + progress * 0.13, status))
            );

            updateUi(() -> setProgressState(0.35, "Проверка файлов сборки..."));
            var fileChecks = fileHashService.checkFiles(manifest.getFiles(), gameDirectory);
            updateUi(() -> logFileChecks(fileChecks));
            ProgressUpdateThrottler progressThrottler = new ProgressUpdateThrottler(150);

            downloadService.downloadMissingOrOutdatedFiles(
                    fileChecks,
                    config.getYandexDiskPublicUrl(),
                    new DownloadService.DownloadProgressListener() {
                @Override
                public void onProgress(long downloadedBytes, long totalBytes) {
                    if (!progressThrottler.shouldPublish(downloadedBytes, totalBytes)) {
                        return;
                    }
                    updateUi(() -> updateProgress(downloadedBytes, totalBytes, 0.38, 0.55));
                }

                @Override
                public void onFileStarted(ManifestFileEntry entry) {
                    updateUi(() -> {
                        setProgressStatus("Скачивание: " + entry.getPath());
                        logService.info("DOWNLOADING | " + entry.getPath());
                    });
                }

                @Override
                public void onFileFinished(ManifestFileEntry entry) {
                    updateUi(() -> logService.info("UPDATED | " + entry.getPath()));
                }

                @Override
                public void onSourceStarted(ManifestFileEntry entry, String sourceName) {
                    if (!"GitHub".equals(sourceName)) {
                        updateUi(() -> logService.info("TRYING " + sourceName + " | " + entry.getPath()));
                    }
                }

                @Override
                public void onSourceFailed(ManifestFileEntry entry, String sourceName, String reason) {
                    updateUi(() -> logService.warn(sourceName
                            + " download failed for "
                            + entry.getPath()
                            + ": "
                            + reason));
                }

                @Override
                public void onDownloadAttempt(
                        ManifestFileEntry entry,
                        String sourceName,
                        int attempt,
                        int maxAttempts,
                        String host
                ) {
                    updateUi(() -> logService.info("DOWNLOAD ATTEMPT "
                            + attempt
                            + "/"
                            + maxAttempts
                            + " | "
                            + sourceName
                            + " | host="
                            + host
                            + " | "
                            + entry.getPath()));
                }

                @Override
                public void onDownloadAttemptFinished(
                        ManifestFileEntry entry,
                        String sourceName,
                        int attempt,
                        String host,
                        int httpStatus,
                        long durationMillis,
                        String result
                ) {
                    String status = httpStatus > 0 ? Integer.toString(httpStatus) : "n/a";
                    updateUi(() -> logService.info("DOWNLOAD RESULT "
                            + attempt
                            + " | "
                            + sourceName
                            + " | host="
                            + host
                            + " | status="
                            + status
                            + " | durationMs="
                            + durationMillis
                            + " | "
                            + entry.getPath()
                            + " | "
                            + result));
                }

                @Override
                public void onDownloadRetryScheduled(
                        ManifestFileEntry entry,
                        String sourceName,
                        int nextAttempt,
                        int maxAttempts,
                        long delayMillis,
                        String reason
                ) {
                    updateUi(() -> logService.warn("Повтор загрузки "
                            + nextAttempt
                            + "/"
                            + maxAttempts
                            + " через "
                            + delayMillis
                            + " мс | "
                            + sourceName
                            + " | "
                            + entry.getPath()
                            + " | "
                            + reason));
                }

                @Override
                public void onTemporaryDownloadDeleted(ManifestFileEntry entry, Path temporaryPath) {
                    updateUi(() -> logService.info("Удален старый временный файл загрузки: "
                            + temporaryPath.getFileName()
                            + " | "
                            + entry.getPath()));
                }
            });

            updateUi(() -> setProgressState(0.94, "Файлы сборки готовы"));
            return new UpdateResult(javaRuntimeInfo, manifest);
        });
            activeUpdateFuture = updateFuture;
        }
        updateFuture.whenComplete((result, throwable) -> {
            synchronized (updateOperationLock) {
                if (activeUpdateFuture == updateFuture) {
                    activeUpdateFuture = null;
                }
            }
        });
        return updateFuture;
    }

    private void validateManagedFoldersBeforeLaunch(
            LauncherManifest manifest,
            Path gameDirectory,
            String nickname
    ) {
        updateUi(() -> logService.info("Validating managed folders: mods, resourcepacks, shaderpacks, tacz..."));
        var unknownFiles = managedFoldersIntegrityService.findUnknownFiles(manifest, gameDirectory);
        if (unknownFiles.isEmpty()) {
            updateUi(() -> logService.info("Managed folder validation passed."));
            return;
        }

        updateUi(() -> {
            logService.warn("Launch blocked: forbidden unknown files detected.");
            for (String unknownFile : unknownFiles) {
                logService.warn(" - " + unknownFile);
            }
        });

        try {
            BackendAuthService.ModerationReportResponse response = backendAuthService.reportIntegrityViolation(
                    config.getLauncherSessionToken(),
                    nickname,
                    manifest.getModpackVersion(),
                    launcherSelfUpdateService.getCurrentVersion(),
                    unknownFiles
            );
            if (response.isOk()) {
                updateUi(() -> logService.info("Moderation alert sent."));
            }
        } catch (RuntimeException exception) {
            updateUi(() -> logService.warn("Unable to send moderation alert: " + exception.getMessage()));
        }

        var deletedFiles = managedFoldersIntegrityService.deleteUnknownFiles(gameDirectory, unknownFiles);
        updateUi(() -> {
            for (String deletedFile : deletedFiles) {
                logService.warn("REMOVED | " + deletedFile);
            }
        });

        var remainingUnknownFiles = managedFoldersIntegrityService.findUnknownFiles(manifest, gameDirectory);
        if (!remainingUnknownFiles.isEmpty()) {
            throw new IllegalStateException("Some forbidden files could not be removed from managed folders.");
        }

        updateUi(() -> logService.info("Forbidden files were removed. Launch may continue."));
    }

    private void logJavaRuntime(JavaRuntimeService.JavaRuntimeInfo javaRuntimeInfo) {
        logService.info("Java executable: " + javaRuntimeInfo.getExecutable());
        logService.info("Java version: " + javaRuntimeInfo.getVersionText());
    }

    private void showDiscordCodeInput() {
        loginWithDiscordButton.setVisible(false);
        loginWithDiscordButton.setManaged(false);
        loginCodeField.setVisible(true);
        loginCodeField.setManaged(true);
        loginCodeField.requestFocus();
    }

    private void hideDiscordCodeInput() {
        loginCodeField.setVisible(false);
        loginCodeField.setManaged(false);
        loginWithDiscordButton.setVisible(true);
        loginWithDiscordButton.setManaged(true);
    }

    private void validateStoredDiscordSessionOnStartup() {
        String sessionTokenForValidation = config.getLauncherSessionToken();
        if (sessionTokenForValidation.isBlank()) {
            return;
        }

        discordSessionValidationPending = true;
        discordSessionValidationFailed = false;
        updateDiscordUi();
        updatePlayButtonState();

        CompletableFuture
                .supplyAsync(() -> backendAuthService.getLauncherSessionInfo(sessionTokenForValidation))
                .whenComplete((response, throwable) -> updateUi(() -> {
                    if (isDiscordLoginFlowActive()
                            || !sessionTokenForValidation.equals(config.getLauncherSessionToken())) {
                        discordSessionValidationPending = false;
                        discordSessionValidationFailed = false;
                        updateDiscordUi();
                        updatePlayButtonState();
                        logService.info("Old Discord session check ignored because a new login is in progress.");
                        return;
                    }

                    discordSessionValidationPending = false;
                    if (throwable != null) {
                        if (isExpiredLauncherSessionError(throwable)) {
                            clearStoredDiscordSession();
                            logService.warn("Discord session expired. Log in again.");
                        } else {
                            discordSessionValidationFailed = true;
                            logService.warn("Discord session check failed: " + getErrorMessage(throwable));
                        }
                        updateDiscordUi();
                        updatePlayButtonState();
                        return;
                    }

                    discordSessionValidationFailed = false;
                    config.setDiscordUsername(response.getUser().getUsername());
                    config.setDiscordUserId(getDiscordUserId(response.getUser()));
                    config.save();
                    updateDiscordUi();
                    updatePlayButtonState();
                    logService.info("Discord session validated: " + response.getUser().getUsername());
                }));
    }

    private boolean isDiscordLoginFlowActive() {
        return loginCodeSubmitting || (loginCodeField != null && loginCodeField.isVisible());
    }

    private void updateDiscordUi() {
        updateDiscordUiFromSessionState();
    }

    private void updatePlayButtonState() {
        if (playButton == null || nicknameField == null) {
            return;
        }

        boolean updateRequired = isMandatoryLauncherUpdateAvailable();
        boolean validNickname = isValidNickname(nicknameField.getText().trim());
        playButton.setText(updateRequired ? UPDATE_BUTTON_TEXT : PLAY_BUTTON_TEXT);
        playButton.setDisable(launcherBusy
                || !launcherUpdateCheckCompleted
                || launcherUpdateCheckBlocked
                || (!updateRequired && (!validNickname || !isDiscordAuthorized())));
    }

    private void updateDiscordUiFromSessionState() {
        boolean authorized = isDiscordAuthorizedStrict();
        boolean hasToken = !config.getLauncherSessionToken().isBlank();
        boolean needsRelogin = hasToken
                && !authorized
                && !discordSessionValidationPending
                && !discordSessionValidationFailed;
        discordStatusLabel.setText("");
        discordStatusLabel.getStyleClass().removeAll("status-ok", "status-fail", "discord-status-ok", "discord-status-fail");
        discordStatusLabel.getStyleClass().add(authorized ? "discord-status-ok" : "discord-status-fail");
        if (authorized) {
            discordUserLabel.setText("Discord подключён: " + config.getDiscordUsername());
        } else if (discordSessionValidationPending) {
            discordUserLabel.setText("Discord: проверка сохраненной сессии...");
        } else if (discordSessionValidationFailed) {
            discordUserLabel.setText("Discord не подключён: войдите заново");
        } else if (needsRelogin) {
            discordUserLabel.setText("Discord не подключён: сессия устарела");
        } else {
            discordUserLabel.setText("Discord не подключён");
        }
    }

    private boolean isDiscordAuthorized() {
        return isDiscordAuthorizedStrict();
    }

    private boolean isDiscordAuthorizedStrict() {
        if (!config.isDiscordAuthEnabled() && !config.isBackendAuthEnabled()) {
            return true;
        }

        return !config.getLauncherSessionToken().isBlank()
                && !config.getDiscordUserId().isBlank()
                && !discordSessionValidationPending
                && !discordSessionValidationFailed;
    }

    private void clearExpiredLauncherSessionIfNeeded(Throwable throwable) {
        if (!isExpiredLauncherSessionError(throwable)) {
            return;
        }

        clearStoredDiscordSession();
        updateDiscordUi();
        logService.warn("Discord session expired. Log in again.");
    }

    private boolean isExpiredLauncherSessionError(Throwable throwable) {
        String message = getErrorMessage(throwable);
        return message.contains("Backend HTTP 401")
                || message.contains("LAUNCHER_SESSION")
                || message.contains("Launcher session is invalid");
    }

    private void clearStoredDiscordSession() {
        config.setLauncherSessionToken("");
        config.setDiscordUsername("");
        config.setDiscordUserId("");
        discordSessionValidationPending = false;
        discordSessionValidationFailed = false;
        config.save();
    }

    private String getDiscordUserId(BackendAuthService.DiscordUser user) {
        return user.getDiscordId().isBlank() ? user.getId() : user.getDiscordId();
    }

    private boolean isValidNickname(String nickname) {
        return NICKNAME_PATTERN.matcher(nickname).matches();
    }

    private String getServerAddressText() {
        if (config.getServerHost().isBlank()) {
            return "not configured";
        }

        return config.getServerHost() + ":" + config.getServerPort();
    }

    private void logManifest(LauncherManifest manifest) {
        logService.info("Manifest loaded.");
        logService.info("Minecraft version: " + manifest.getMinecraftVersion());
        logService.info("Forge version: " + manifest.getForgeVersion());
        logService.info("Modpack version: " + manifest.getModpackVersion());

        if (manifest.getFiles().isEmpty()) {
            logService.info("Files: none");
            return;
        }

        logService.info("Files:");
        for (ManifestFileEntry file : manifest.getFiles()) {
            logService.info(" - " + file.getPath()
                    + " | size=" + file.getSize()
                    + " | required=" + file.isRequired()
                    + " | sha256=" + file.getSha256()
                    + " | url=" + file.getUrl());
        }
    }

    private void logFileChecks(Iterable<FileHashService.FileCheckResult> results) {
        logService.info("Local file check:");
        for (FileHashService.FileCheckResult result : results) {
            logService.info(" - " + result.getStatus()
                    + " | " + result.getManifestPath()
                    + " | local=" + result.getLocalPath());
        }
    }

    private void updateProgress(long downloadedBytes, long totalBytes) {
        updateProgress(downloadedBytes, totalBytes, 0.0, 1.0);
    }

    private void updateProgress(long downloadedBytes, long totalBytes, double start, double weight) {
        if (totalBytes <= 0) {
            setProgressState(start + weight, "Файлы сборки готовы");
            return;
        }

        double downloadProgress = Math.min(1.0, Math.max(0.0, (double) downloadedBytes / totalBytes));
        double progress = Math.min(1.0, Math.max(0.0, start + downloadProgress * weight));
        setProgressState(progress, "Скачивание файлов сборки...");
    }

    private void setProgressState(double progress, String status) {
        double normalizedProgress = Math.min(1.0, Math.max(0.0, progress));
        String normalizedStatus = status == null || status.isBlank() ? "Готово" : status;
        boolean activeProgress = launcherBusy
                || normalizedStatus.endsWith("...")
                || (normalizedProgress > 0.0
                        && normalizedProgress < 1.0
                        && isActiveProgressStatus(normalizedStatus));

        progressBar.setProgress(activeProgress ? normalizedProgress : 0.0);
        progressPercentLabel.setText(activeProgress ? Math.round(normalizedProgress * 100.0) + "%" : "");
        progressPercentLabel.setVisible(activeProgress);
        progressPercentLabel.setManaged(activeProgress);
        activityIndicator.setVisible(activeProgress);
        activityIndicator.setManaged(activeProgress);
        progressStatusLabel.setText(normalizedStatus);
        progressStatusLabel.setTooltip(new Tooltip(normalizedStatus));
    }

    private void setProgressStatus(String status) {
        double progress = progressBar.getProgress();
        if (progress < 0) {
            progress = 0.0;
        }
        setProgressState(progress, status);
    }

    private boolean isActiveProgressStatus(String status) {
        String normalized = status.toLowerCase();
        if (normalized.contains("ошибка") || normalized.contains("закрыт") || normalized.contains("готово")) {
            return false;
        }
        return normalized.contains("...")
                || normalized.contains("скачив")
                || normalized.contains("провер")
                || normalized.contains("подготов")
                || normalized.contains("установ")
                || normalized.contains("загруз")
                || normalized.contains("запуск")
                || normalized.contains("обнов");
    }

    private void updateUi(Runnable action) {
        Platform.runLater(action);
    }

    private boolean isMandatoryLauncherUpdateAvailable() {
        return availableLauncherUpdate != null && availableLauncherUpdate.isMandatory();
    }

    private boolean isUpdateOperationRunning() {
        synchronized (updateOperationLock) {
            return activeUpdateFuture != null && !activeUpdateFuture.isDone();
        }
    }

    private boolean isTemporaryLauncherUpdateFailure(Throwable throwable) {
        Throwable cause = unwrapCompletionException(throwable);
        return cause instanceof LauncherSelfUpdateService.SelfUpdateException selfUpdateException
                && selfUpdateException.isTemporaryFailure();
    }

    private String getErrorMessage(Throwable throwable) {
        Throwable cause = unwrapCompletionException(throwable);
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private Throwable unwrapCompletionException(Throwable throwable) {
        Throwable cause = throwable;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static final class ProgressUpdateThrottler {
        private static final double MIN_PROGRESS_DELTA = 0.005;

        private final long minIntervalNanos;
        private long lastPublishedAtNanos;
        private double lastPublishedProgress = -1.0;

        private ProgressUpdateThrottler(long minIntervalMillis) {
            this.minIntervalNanos = minIntervalMillis * 1_000_000L;
        }

        private boolean shouldPublish(long downloadedBytes, long totalBytes) {
            double progress = totalBytes <= 0
                    ? 1.0
                    : Math.min(1.0, Math.max(0.0, (double) downloadedBytes / (double) totalBytes));
            long now = System.nanoTime();
            boolean isFinalUpdate = totalBytes > 0 && downloadedBytes >= totalBytes;
            if (lastPublishedProgress < 0.0
                    || isFinalUpdate
                    || now - lastPublishedAtNanos >= minIntervalNanos
                    || Math.abs(progress - lastPublishedProgress) >= MIN_PROGRESS_DELTA) {
                lastPublishedAtNanos = now;
                lastPublishedProgress = progress;
                return true;
            }
            return false;
        }
    }

    private record UpdateResult(JavaRuntimeService.JavaRuntimeInfo javaRuntimeInfo, LauncherManifest manifest) {
    }
}
