package net.jan.moddirector.core;

import net.jan.moddirector.core.configuration.ModDirectorRemoteMod;
import net.jan.moddirector.core.logging.ModDirectorSeverityLevel;
import net.jan.moddirector.core.logging.ModDirectorLogger;
import net.jan.moddirector.core.configuration.ConfigurationController;
import net.jan.moddirector.core.manage.InstallController;
import net.jan.moddirector.core.manage.InstalledMod;
import net.jan.moddirector.core.manage.ModDirectorError;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ModDirector {
    private static ModDirector instance;

    public static ModDirector bootstrap(ModDirectorPlatform platform) {
        if(instance != null) {
            throw new IllegalStateException("ModDirector has already been bootstrapped using platform " +
                    platform.name());
        }

        instance = new ModDirector(platform);
        instance.bootstrap();
        return instance;
    }

    public static ModDirector getInstance() {
        if(instance == null) {
            throw new IllegalStateException("ModDirector has not been bootstrapped yet");
        }

        return instance;
    }

    private final ModDirectorPlatform platform;
    private final ModDirectorLogger logger;
    private final ConfigurationController configurationController;
    private final InstallController installController;
    private final List<ModDirectorError> errors;
    private final List<InstalledMod> installedMods;
    private final ExecutorService executorService;

    private ModDirector(ModDirectorPlatform platform) {
        this.platform = platform;
        this.logger = platform.logger();

        this.configurationController = new ConfigurationController(this, platform.configurationDirectory());
        this.installController = new InstallController(this);

        this.errors = new LinkedList<>();
        this.installedMods = new LinkedList<>();
        this.executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() / 2);

        logger.log(ModDirectorSeverityLevel.INFO, "ModDirector", "CORE", "Mod director loaded!");
    }

    private void bootstrap() {
        platform.bootstrap();
    }

    public boolean activate(long timeout, TimeUnit timeUnit) throws InterruptedException {
        List<ModDirectorRemoteMod> mods = configurationController.load();
        if(hasFatalError()) {
            return false;
        }

        mods.forEach(mod -> executorService.submit(() -> {
            try {
                installController.handle(mod);
            } catch(Exception e) {
                logger.logThrowable(ModDirectorSeverityLevel.ERROR, "ModDirector", "CORE", e,
                        "Unhandled exception in worker thread");
                addError(new ModDirectorError(ModDirectorSeverityLevel.ERROR,
                        "Unhandled exception in worker thread", e));
            }
        }));
        executorService.shutdown();
        executorService.awaitTermination(timeout, timeUnit);

        return !hasFatalError();
    }

    public ModDirectorLogger getLogger() {
        return logger;
    }

    public ModDirectorPlatform getPlatform() {
        return platform;
    }

    public void addError(ModDirectorError error) {
        synchronized(errors) {
            errors.add(error);
        }
    }

    public boolean hasFatalError() {
        return errors.stream().anyMatch(e -> e.getLevel() == ModDirectorSeverityLevel.ERROR);
    }

    public void installSuccess(InstalledMod mod) {
        synchronized(installedMods) {
            installedMods.add(mod);
        }
    }

    public List<InstalledMod> getInstalledMods() {
        return Collections.unmodifiableList(installedMods);
    }

    public void errorExit() {
        // TODO: display errors
        System.exit(1);
    }
}