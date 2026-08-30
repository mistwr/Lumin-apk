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

        if (n.contains("poupar") || n.contains("quero falar com") || n.contains("consultor") || n.contains("falar com uma pessoa")) {
            memory.put("handoff", true);
            return new Decision("Perfeito. Vou deixar o pedido preparado para um consultor MyPoupar verificar consigo a melhor opção.", true, "HANDOFF", true);
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
    }

    public static String buildPrompt(String customer, SofiaMemory memory) {
        return "És a SOFIA, consultora MyPoupar. Fala em português de Portugal, como uma pessoa ao telefone. " +
                "Responde normalmente numa frase curta e faz no máximo uma pergunta. Nunca repitas factos já conhecidos. " +
                "Mantém a conversa e qualifica o cliente passo a passo. NÃO termines a conversa nem encaminhes para consultor apenas porque o cliente pergunta 'qual é a melhor opção', 'quanto fica' ou pede uma recomendação. " +
                "Só deves fazer handoff para humano quando o cliente pedir explicitamente uma pessoa/consultor ou disser 'poupar'. Caso contrário continua a analisar e faz a próxima pergunta útil. " +
                "Factos conhecidos: " + memory.summary() + ". " +
                "Cliente disse: " + customer + ". " +
                "A tua resposta anterior foi: " + memory.getLastAssistant() + ". " +
                "Se faltar informação, faz apenas a próxima pergunta útil. Se houver objeção, responde com empatia e objetividade. " +
                "Não inventes preços. Se o cliente pedir preço atual, diz que precisas dos dados necessários para comparar e continua a qualificação.";
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
