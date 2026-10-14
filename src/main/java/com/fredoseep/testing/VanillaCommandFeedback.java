package com.fredoseep.testing;

import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;

import java.util.ArrayList;
import java.util.List;

/** Parse translation keys/arguments, not Chinese/English rendered sentences. */
public final class VanillaCommandFeedback {
    private VanillaCommandFeedback() {}

    public static TranslatableText find(Text root, String key) {
        if (root instanceof TranslatableText translated) {
            if (translated.getKey().equals(key)) return translated;
            for (Object arg : translated.getArgs()) {
                if (arg instanceof Text text) {
                    TranslatableText found = find(text, key);
                    if (found != null) return found;
                }
            }
        }
        for (Text sibling : root.getSiblings()) {
            TranslatableText found = find(sibling, key);
            if (found != null) return found;
        }
        return null;
    }

    public static List<String> keys(Text root) {
        List<String> result = new ArrayList<>();
        collect(root, result);
        return result;
    }

    private static void collect(Text root, List<String> result) {
        if (root instanceof TranslatableText translated) {
            result.add(translated.getKey());
            for (Object arg : translated.getArgs()) if (arg instanceof Text text) collect(text, result);
        }
        for (Text sibling : root.getSiblings()) collect(sibling, result);
    }

    public static Long seed(Text message) {
        TranslatableText reply = find(message, "commands.seed.success");
        if (reply == null || reply.getArgs().length != 1) return null;
        Object arg = reply.getArgs()[0];
        if (arg instanceof Text text) {
            String copied = copiedSeed(text);
            if (copied != null) return parseLong(copied);
            return parseLong(text.getString().replace("[", "").replace("]", "").trim());
        }
        return parseLong(String.valueOf(arg));
    }

    private static String copiedSeed(Text text) {
        ClickEvent click = text.getStyle().getClickEvent();
        if (click != null && click.getAction() == ClickEvent.Action.COPY_TO_CLIPBOARD) return click.getValue();
        if (text instanceof TranslatableText translated) {
            for (Object arg : translated.getArgs()) {
                if (arg instanceof Text child) {
                    String found = copiedSeed(child);
                    if (found != null) return found;
                }
            }
        }
        for (Text child : text.getSiblings()) {
            String found = copiedSeed(child);
            if (found != null) return found;
        }
        return null;
    }

    private static Long parseLong(String text) {
        try { return Long.parseLong(text.trim()); }
        catch (NumberFormatException ignored) { return null; }
    }

    public static int[] treasure(Text message) {
        TranslatableText reply = find(message, "commands.locate.success");
        if (reply == null || reply.getArgs().length < 2 || !(reply.getArgs()[1] instanceof Text coordinates)) return null;
        TranslatableText position = find(coordinates, "chat.coordinates");
        if (position == null || position.getArgs().length != 3) return null;
        try {
            return new int[]{Integer.parseInt(String.valueOf(position.getArgs()[0])),
                    Integer.parseInt(String.valueOf(position.getArgs()[2]))};
        } catch (NumberFormatException ignored) { return null; }
    }
}
