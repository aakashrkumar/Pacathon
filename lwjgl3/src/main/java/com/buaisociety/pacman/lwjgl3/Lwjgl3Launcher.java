package com.buaisociety.pacman.lwjgl3;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.buaisociety.pacman.Main;
import com.buaisociety.pacman.Tournament;

/** Launches the desktop (LWJGL3) application. */
public class Lwjgl3Launcher {
    public static void main(String[] args) {
        if (StartupHelper.startNewJvmIfRequired()) return; // This handles macOS support and helps on Windows.
        createApplication();
    }

    private static Lwjgl3Application createApplication() {
        boolean isTraining = false; // set this as false to try out the tournament settings
        if (isTraining) {
            Lwjgl3ApplicationConfiguration config = getDefaultConfiguration();
            // disable vsync to run the game as fast as possible
            config.useVsync(false);
            config.setForegroundFPS(2000);

            // hard limit on fps to see the game running at a reasonable speed
             return new Lwjgl3Application(new Main(), config);
        } else {
            Lwjgl3ApplicationConfiguration config = getDefaultConfiguration();
            // enable vsync to limit the fps to the monitor refresh rate
            config.useVsync(false);
            // pacman runs at 60 updates per second
            config.setForegroundFPS(240);
            return new Lwjgl3Application(new Tournament(), config);
        }
    }

    private static Lwjgl3ApplicationConfiguration getDefaultConfiguration() {
        Lwjgl3ApplicationConfiguration configuration = new Lwjgl3ApplicationConfiguration();
        configuration.setTitle("Pacathon");
        configuration.setWindowedMode(640, 480);
        configuration.setWindowIcon("logo.png");
        return configuration;
    }
}
