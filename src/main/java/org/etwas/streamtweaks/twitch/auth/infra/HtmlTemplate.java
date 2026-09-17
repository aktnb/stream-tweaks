package org.etwas.streamtweaks.twitch.auth.infra;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

public final class HtmlTemplate {

    private HtmlTemplate() {}

    public static String render(String templateName, Map<String, String> parameters) {
        try (InputStream is = HtmlTemplate.class.getResourceAsStream("/html/" + templateName)) {
            if (is == null) {
                throw new RuntimeException("Template not found: " + templateName);
            }

            String template = new String(is.readAllBytes());

            for (Map.Entry<String, String> entry : parameters.entrySet()) {
                template = template.replace("{{" + entry.getKey() + "}}", entry.getValue());
            }

            return template;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load HTML template: " + templateName, e);
        }
    }
}
