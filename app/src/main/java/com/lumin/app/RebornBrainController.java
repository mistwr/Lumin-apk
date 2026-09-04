package com.lumin.app;

/**
 * Orchestrates transcript -> decision -> response flow.
 * Audio and CRM integrations plug into this controller.
 */
public class RebornBrainController {
    private final RebornCallSession session;

    public RebornBrainController(RebornCallSession session) {
        this.session = session;
    }

    public String analyse(String customerText) {
        session.appendTranscript("CLIENTE: " + customerText);
        session.setStage("ANALYSE");

        if (customerText == null || customerText.trim().isEmpty()) {
            return "Pode repetir, por favor?";
        }

        String text = customerText.toLowerCase();
        if (text.contains("preço") || text.contains("pago")) {
            session.setStage("DISCOVERY");
            return "Para comparar consigo a melhor solução, diga-me qual o serviço que tem atualmente e quanto paga por mês.";
        }

        session.setStage("QUALIFICATION");
        return "Vou perceber a sua situação para encontrar a solução mais adequada.";
    }
}
