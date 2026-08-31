package com.lumin.app;

/** Built-in REBORN AI agent profiles. One engine, multiple commercial personalities. */
public final class RebornAgentCatalog {
    public static final class AgentProfile {
        public final String id;
        public final String displayName;
        public final String name;
        public final String brand;
        public final String tone;
        public final String objective;
        public final String script;
        public final String opening;

        AgentProfile(String id, String displayName, String name, String brand, String tone,
                     String objective, String script, String opening) {
            this.id = id;
            this.displayName = displayName;
            this.name = name;
            this.brand = brand;
            this.tone = tone;
            this.objective = objective;
            this.script = script;
            this.opening = opening;
        }
    }

    private static final AgentProfile[] AGENTS = new AgentProfile[] {
            new AgentProfile(
                    "sofia_sales",
                    "SOFIA · Consultora Comercial",
                    "SOFIA",
                    "MyPoupar",
                    "consultivo, natural, profissional e comercial",
                    "Qualificar o cliente, descobrir poupança em telecomunicações e energia e conduzir a próxima ação comercial.",
                    "Segue o funil MyPoupar e os guiões/objeções ativos do SD Dialer. Faz uma pergunta de cada vez. Guarda factos já dados. Prioriza qualificação, necessidade, comparação e fecho. Não inventes preços nem cobertura. Faz handoff apenas com intenção explícita.",
                    "Olá. Falo da MyPoupar. Posso fazer-lhe duas perguntas rápidas para perceber se consegue poupar?"
            ),
            new AgentProfile(
                    "lumin_savings",
                    "LUMIN · Assistente de Poupança",
                    "LUMIN",
                    "MyPoupar",
                    "calmo, claro, útil, consultivo e próximo",
                    "Analisar a situação do cliente e encontrar oportunidades de poupança em telecomunicações e energia sem pressão comercial.",
                    "Usa o método MyPoupar, dados já conhecidos e os guiões do SD Dialer apenas quando relevantes. Explica de forma simples, faz uma pergunta de cada vez e compara antes de recomendar. Não inventes preços. Encaminha para consultor quando o cliente pedir adesão, contacto humano ou disser claramente que quer poupar.",
                    "Olá. Sou o Lumin da MyPoupar. Posso ajudar a perceber onde consegue poupar?"
            )
    };

    private RebornAgentCatalog() {}

    public static int size() { return AGENTS.length; }
    public static AgentProfile at(int index) {
        if (index < 0 || index >= AGENTS.length) return AGENTS[0];
        return AGENTS[index];
    }
    public static String[] labels() {
        String[] out = new String[AGENTS.length];
        for (int i=0;i<AGENTS.length;i++) out[i] = AGENTS[i].displayName;
        return out;
    }
    public static int indexFor(String idOrName) {
        if (idOrName == null) return 0;
        for (int i=0;i<AGENTS.length;i++) {
            AgentProfile a = AGENTS[i];
            if (a.id.equalsIgnoreCase(idOrName) || a.name.equalsIgnoreCase(idOrName)) return i;
        }
        return 0;
    }
}
