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
        String n = normalize(t);

        // Handoff only when the intent is explicit. A normal question such as
        // "como posso poupar na eletricidade?" must continue the conversation.
        boolean explicitHandoff = n.equals("poupar") || n.equals("quero poupar") ||
                n.contains("quero falar com um consultor") || n.contains("quero falar com consultor") ||
                n.contains("quero falar com uma pessoa") || n.contains("passa me a um consultor") ||
                n.contains("passe me a um consultor") || n.contains("falar com humano");
        if (explicitHandoff) {
            memory.put("handoff", true);
            return new Decision("Perfeito. Vou deixar o pedido preparado para um consultor MyPoupar verificar consigo a melhor opção.", true, "HANDOFF", true);
        }

        // Very common call turns should be instant and not wait for the LLM.
        if (n.equals("estas ai") || n.equals("esta ai") || n.equals("ola") || n.equals("alo")) {
            return new Decision("Sim, estou consigo. Diga-me: quer analisar telecomunicações, eletricidade ou os dois?", true, "OPENING", false);
        }
        if ((n.contains("poupar") || n.contains("baixar")) && (n.contains("eletric") || n.contains("energia") || n.contains("luz"))) {
            memory.put("energy_interest", true);
            return new Decision("Claro. Para começar, sensivelmente quanto paga por mês de eletricidade?", true, "ENERGY_QUALIFICATION", false);
        }
        if ((n.contains("melhor opcao") || n.contains("mais barato") || n.contains("pagar menos")) && !memory.has("operator")) {
            return new Decision("Consigo comparar consigo. Atualmente está com que operador?", true, "QUALIFICATION", false);
        }

        Matcher mm = MOBILE_LINES.matcher(t);
        if (mm.find()) memory.put("mobile_lines", Integer.parseInt(mm.group(1)));

        Matcher pm = PRICE.matcher(t);
        if (pm.find()) {
            try { memory.put("monthly_price", Double.parseDouble(pm.group(1).replace(',', '.'))); } catch (Exception ignored) {}
        }

        Matcher cp = POSTAL.matcher(t);
        if (cp.find()) memory.put("postal_code", cp.group(1) + "-" + cp.group(2));

        String operator = detectOperator(n);
        if (operator != null) memory.put("operator", operator);
        if (n.contains("televisao") || n.contains("tv")) memory.put("tv", true);
        if (n.contains("internet") || n.contains("fibra") || n.contains("wifi")) memory.put("internet", true);

        if (memory.has("mobile_lines") && !memory.has("operator")) {
            return new Decision("Certo. E atualmente está com que operador?", true, "QUALIFICATION", false);
        }
        if (memory.has("operator") && !memory.has("monthly_price")) {
            return new Decision("E sensivelmente quanto paga por mês pelo pacote?", true, "QUALIFICATION", false);
        }
        if (memory.has("monthly_price") && !memory.has("postal_code")) {
            return new Decision("Qual é o seu código postal para eu confirmar o que está disponível na sua zona?", true, "QUALIFICATION", false);
        }
        if (memory.has("postal_code") && !memory.has("satisfaction")) {
            return new Decision("Está satisfeito com o serviço ou há alguma coisa que gostava de melhorar?", true, "NEEDS", false);
        }

        return null;
    }

    public static void learnFreeText(String customer, SofiaMemory memory) {
        String n = normalize(customer);
        if (n.contains("satisfeito") || n.contains("estou bem") || n.contains("sem problemas")) memory.put("satisfaction", "satisfied");
        if (n.contains("caro") || n.contains("pago muito") || n.contains("baixar") || n.contains("preco")) memory.put("main_problem", "price");
        if (n.contains("internet lenta") || n.contains("wifi") || n.contains("falha")) memory.put("main_problem", "quality");
        if (n.contains("eletric") || n.contains("energia") || n.contains("luz")) memory.put("energy_interest", true);
    }

    public static String buildPrompt(String customer, SofiaMemory memory) {
        return "És a SOFIA, consultora MyPoupar. Português de Portugal, tom natural de chamada. " +
                "Responde numa frase curta, no máximo 18 palavras, e faz no máximo uma pergunta. Nunca repitas factos conhecidos. " +
                "Continua a qualificação; não termines nem faças handoff só porque o cliente pergunta preços, poupança ou melhor opção. " +
                "Handoff apenas se pedir explicitamente uma pessoa/consultor ou disser exatamente que quer avançar/poupar. " +
                "Factos: " + memory.summary() + ". Cliente: " + customer + ". Resposta anterior: " + memory.getLastAssistant() + ". " +
                "Se faltar informação, pergunta apenas o próximo dado útil. Não inventes preços.";
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
                .replace('ô','o').replace('õ','o').replace('ú','u').replace('ç','c')
                .replace("?", "").replace("!", "").replace(".", "").trim();
    }
}
