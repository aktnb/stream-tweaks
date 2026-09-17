package org.etwas.streamtweaks.mod;

import org.etwas.streamtweaks.application.TwitchApplicationService;

public final class TwitchApplicationServices {
    private static TwitchApplicationService applicationService;

    private TwitchApplicationServices() {}

    public static TwitchApplicationService get() {
        return applicationService;
    }

    public static void set(TwitchApplicationService applicationService) {
        TwitchApplicationServices.applicationService = applicationService;
    }
}
