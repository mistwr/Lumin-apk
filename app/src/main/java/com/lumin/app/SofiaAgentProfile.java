package com.lumin.app;

/** In-process active profile used by the calling cockpit and the local LLM prompt. */
public final class SofiaAgentProfile {
    private static volatile String agentName = "SOFIA";
    private static volatile String brand = "MyPoupar";
    private static volatile String tone = "consultivo, natural e profissional";
    private static volatile String objective = "Ajudar o cliente a poupar em telecomunicações e energia";
    private static volatile String script = "Qualifica primeiro. Faz uma pergunta de cada vez. Não inventes preços. Só faz handoff quando houver intenção explícita.";
    private static volatile String opening = "Olá. Falo da MyPoupar. Posso fazer-lhe duas perguntas rápidas para perceber se consegue poupar?";

    private SofiaAgentProfile() {}

    public static void configure(String name, String company, String newTone, String newObjective, String newScript, String newOpening) {
        if (name != null && !name.trim().isEmpty()) agentName = name.trim();
        if (company != null && !company.trim().isEmpty()) brand = company.trim();
        if (newTone != null && !newTone.trim().isEmpty()) tone = newTone.trim();
        if (newObjective != null && !newObjective.trim().isEmpty()) objective = newObjective.trim();
        if (newScript != null && !newScript.trim().isEmpty()) script = newScript.trim();
        if (newOpening != null && !newOpening.trim().isEmpty()) opening = newOpening.trim();
    }

    public static String name() { return agentName; }
    public static String brand() { return brand; }
    public static String opening() { return opening; }

    public static String promptContext() {
        return "Agente: " + agentName + ". Marca: " + brand + ". Tom: " + tone + ". Objetivo: " + objective + ". Script ativo: " + script + ". ";
    }
}
