package com.hcserverapi;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import java.io.IOException;
import java.util.logging.Level;

public class HC_ServerAPIPlugin extends JavaPlugin {

    private HttpService httpService;

    public HC_ServerAPIPlugin(@NonNullDecl JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        getLogger().at(Level.INFO).log("HC_ServerAPI setting up...");

        int port = Integer.parseInt(System.getenv().getOrDefault("HC_API_PORT", "7070"));
        String apiKey = System.getenv().getOrDefault("HC_API_KEY", "changeme");

        try {
            httpService = new HttpService(port, apiKey, getLogger());
        } catch (IOException e) {
            getLogger().at(Level.SEVERE).log("Failed to create HTTP server on port " + port + ": " + e.getMessage());
            return;
        }

        httpService.start();
        getLogger().at(Level.INFO).log("HC_ServerAPI started on port " + port);
    }

    @Override
    protected void start() {
        // Nothing needed - HTTP server is already running from setup()
    }

    @Override
    protected void shutdown() {
        if (httpService != null) {
            httpService.stop();
            getLogger().at(Level.INFO).log("HC_ServerAPI stopped.");
        }
    }
}
