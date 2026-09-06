package com.lumin.app;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SofiaEngine {
    public static class Decision {
        public final String reply;
        public final boolean fastPath;
        public final String stage;
        public final boolean handoff;
        public Decision(String reply, boolean fastPath, String stage, boolean handoff) {
            this.reply = reply; this.fastPath = fastPath; this.stage = stage; this.handoff = handoff;
        }
    }

    private static final Pattern MOBILE_LINES = Pattern.compile("\\b(\\d{1,2})\\s*(?:telem[oó]veis|moveis|móveis|cart[oõ]es|linhas)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRICE = Pattern.compile("\\b(\\d{2,3}(?:[.,]\\d{1,2})?)\\s*(?:€|euros?)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern POSTAL = Pattern.compile("\\b(\\d{4})[- ]?(\\d{3})\\b");

    public static Decision fastDecision(String customer, SofiaMemory memory) {
        if (customer == null) customer = "";
        String t = customer.trim();
        String n = normalize(t).replaceAll("[^a-z0-9 ]+", " ").replaceAll("\\s+", " ").trim();

        if (n.contains("poupar") || n.contains("quero falar com") || n.contains("consultor")) {
            memory.put("handoff", true);
            return new Decision("Perfeito. Vou deixar o pedido preparado para um consultor MyPoupar verificar consigo a melhor opção.", true, "HANDOFF", true);
        }

        // Consentimento curto depois da abertura: não mandar um simples "sim/diga/pode"
        // para o Qwen local. Isto elimina latências enormes no primeiro turno real.
        boolean affirmative = n.equals("sim") || n.equals("sim diga") || n.equals("diga") ||
                n.equals("pode") || n.equals("sim pode") || n.equals("claro") ||
                n.equals("forca") || n.equals("forca diga") || n.equals("vamos") ||
                n.equals("pode falar") || n.equals("sim pode falar");
        if (affirmative && !memory.has("operator")) {
            memory.put("consent", true);
            memory.put("asked_operator", true);
            return new Decision("Perfeito. Atualmente está com que operador?", true, "QUALIFICATION", false);
        }

        // Reclamações, objeções e pedidos de esclarecimento devem ir ao Qwen.
        if (n.contains("nao percebi") || n.contains("repete") || n.contains("repetir") ||
                n.contains("estas sempre") || n.contains("porque") || n.contains("por que") ||
                n.contains("nao quero") || n.contains("agora nao") ||
                n.contains("liga mais tarde") || n.contains("ligue mais tarde")) {
            return null;
        }

        boolean learnedSomething = false;

        Matcher mm = MOBILE_LINES.matcher(t);
        if (mm.find()) {
            memory.put("mobile_lines", Integer.parseInt(mm.group(1)));
            learnedSomething = true;
        }

        Matcher pm = PRICE.matcher(t);
        if (pm.find()) {
            try {
                memory.put("monthly_price", Double.parseDouble(pm.group(1).replace(',', '.')));
                learnedSomething = true;
            } catch (Exception ignored) {}
        }

        Matcher cp = POSTAL.matcher(t);
        if (cp.find()) {
            memory.put("postal_code", cp.group(1) + "-" + cp.group(2));
            learnedSomething = true;
        }

        String operator = detectOperator(n);
        if (operator != null) {
            memory.put("operator", operator);
            learnedSomething = true;
        }
        if (n.contains("televisao") || n.contains("tv")) { memory.put("tv", true); learnedSomething = true; }
        if (n.contains("internet") || n.contains("fibra") || n.contains("wifi")) { memory.put("internet", true); learnedSomething = true; }

        if (!learnedSomething) return null;

        if (memory.has("mobile_lines") && !memory.has("operator") && !memory.has("asked_operator")) {
            memory.put("asked_operator", true);
            return new Decision("Certo. E atualmente está com que operador?", true, "QUALIFICATION", false);
        }
        if (memory.has("operator") && !memory.has("monthly_price") && !memory.has("asked_monthly_price")) {
            memory.put("asked_monthly_price", true);
            return new Decision("E sensivelmente quanto paga por mês pelo pacote?", true, "QUALIFICATION", false);
        }
        if (memory.has("monthly_price") && !memory.has("postal_code") && !memory.has("asked_postal_code")) {
            memory.put("asked_postal_code", true);
            return new Decision("Qual é o seu código postal para eu confirmar o que está disponível na sua zona?", true, "QUALIFICATION", false);
        }
        if (memory.has("postal_code") && !memory.has("satisfaction") && !memory.has("asked_satisfaction")) {
            memory.put("asked_satisfaction", true);
            return new Decision("Está satisfeito com o serviço ou há alguma coisa que gostava de melhorar?", true, "NEEDS", false);
        }

        return null;
    }

    public static void learnFreeText(String customer, SofiaMemory memory) {
        String n = normalize(customer);
        if (n.contains("satisfeito") || n.contains("estou bem") || n.contains("sem problemas")) memory.put("satisfaction", "satisfied");
        if (n.contains("caro") || n.contains("pago muito") || n.contains("baixar") || n.contains("preco")) memory.put("main_problem", "price");
        if (n.contains("internet lenta") || n.contains("wifi") || n.contains("falha")) memory.put("main_problem", "quality");
        if (n.contains("mais tarde") || n.contains("agora nao")) memory.put("callback_requested", true);
    }

    public static String buildPrompt(String customer, SofiaMemory memory) {
        return "És a SOFIA, assistente comercial pessoal da MyPoupar, a falar numa chamada real. " +
                "Fala sempre em português de Portugal, natural, simples e humano. Nunca pareças um questionário. " +
                "A tua missão é perceber a situação atual do cliente em telecomunicações e energia, encontrar poupança e encaminhar quando fizer sentido. " +
                "Responde em uma ou duas frases curtas e faz no máximo UMA pergunta por turno. " +
                "NUNCA repitas exatamente a última pergunta. Se o cliente disser que não percebeu, reformula com palavras diferentes. " +
                "Se o cliente reclamar que estás a repetir, pede desculpa em poucas palavras e muda imediatamente de abordagem. " +
                "Se pedir para ligar mais tarde, confirma de forma curta e não continues a vender. " +
                "Nunca inventes preços, campanhas, cobertura, faturas ou resultados. Quando faltar informação comercial atual, diz que vais confirmar. " +
                "Usa estes passos apenas como orientação, sem os recitar: abertura curta; diagnóstico do operador/preço/fidelização/energia; necessidade; proposta; fecho ou follow-up. " +
                "Factos conhecidos: " + memory.summary() + ". " +
                "Cliente disse agora: " + customer + ". " +
                "A tua resposta anterior foi: " + memory.getLastAssistant() + ". " +
                "Responde apenas com aquilo que a Sofia deve dizer ao cliente, sem notas nem explicações.";
    }

    private static String detectOperator(String n) {
        if (n.contains("meo")) return "MEO";
        if (n.contains("nos")) return "NOS";
        if (n.contains("vodafone")) return "Vodafone";
        if (n.contains("digi")) return "DIGI";
        if (n.contains("nowo")) return "NOWO";
        return null;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT)
                .replace('á','a').replace('à','a').replace('ã','a').replace('â','a')
                .replace('é','e').replace('ê','e').replace('í','i').replace('ó','o')
                .replace('ô','o').replace('õ','o').replace('ú','u').replace('ç','c');
    }
}
