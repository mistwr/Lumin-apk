package com.lumin.app;

/**
 * Compatibility shim kept so the existing call pipeline does not need to change names.
 * Generation is now 100% on-device through LocalRebornEngine.
 */
public class QwenClient {
    public static String generate(String prompt) throws Exception {
        return LocalRebornEngine.generate(SofiaApp.context(), prompt);
    }
}
