package com.solarbiscuit.atmosphera.atmosphera;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.solarbiscuit.atmosphera.atmosphera.sky.SkyEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * {@code /atmosphera skyeffect} (0.7.0): shows one of the sky's occasional sights at will, to look at it or to test it.
 * <pre>
 * /atmosphera skyeffect list                    every effect, whether it is forced and how strongly it shows now
 * /atmosphera skyeffect &lt;effect&gt; [on|off|auto]  force it on (the default) or off, or hand it back to chance
 * /atmosphera skyeffect nebula on &lt;n&gt; [count]   the n-th nebula image (0.9.0; the reply names it), and up to
 *                                            3 nebulae (0.10.0)
 * /atmosphera skyeffect clear                   everything back to chance
 * </pre>
 * A client command: it needs no permission, works on any server, and changes only this player's view. Nothing is
 * saved; forced effects end with the session. Forcing skips the luck and the biome and weather conditions, but an effect
 * still needs what it takes to draw it (a rainbow needs the sun, a blood moon the night); the reply says so.
 */
public final class AtmospheraCommand {
    private AtmospheraCommand() {
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> sky = Commands.literal("skyeffect");
        sky.then(Commands.literal("list").executes(ctx -> list(ctx.getSource())));
        sky.then(Commands.literal("clear").executes(ctx -> {
            SkyEvents.clearForced();
            ctx.getSource().sendSuccess(() -> Component.literal("Sky effects: all back to chance."), false);
            return 1;
        }));
        for (SkyEvents.Effect effect : SkyEvents.Effect.values()) {
            sky.then(node(effect.id, effect));
        }
        // "red moon" is the blood moon.
        sky.then(node("red_moon", SkyEvents.Effect.BLOOD_MOON));
        dispatcher.register(Commands.literal("atmosphera").then(sky));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> node(String name, SkyEvents.Effect effect) {
        LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal(name)
                .executes(ctx -> set(ctx.getSource(), effect, SkyEvents.ON, 0));
        LiteralArgumentBuilder<CommandSourceStack> on = Commands.literal("on")
                .executes(ctx -> set(ctx.getSource(), effect, SkyEvents.ON, 0));
        if (effect == SkyEvents.Effect.NEBULA) {
            on.then(Commands.argument("type", IntegerArgumentType.integer(1, 999))
                    .executes(ctx -> set(ctx.getSource(), effect, SkyEvents.ON, IntegerArgumentType.getInteger(ctx, "type")))
                    // 0.10.0: and how many nebulae (1-3).
                    .then(Commands.argument("count", IntegerArgumentType.integer(1, 3))
                            .executes(ctx -> {
                                int type = IntegerArgumentType.getInteger(ctx, "type");
                                int count = IntegerArgumentType.getInteger(ctx, "count");
                                set(ctx.getSource(), effect, SkyEvents.ON, type);
                                SkyEvents.forceNebula(type, count);
                                return 1;
                            })));
        }
        node.then(on);
        node.then(Commands.literal("off").executes(ctx -> set(ctx.getSource(), effect, SkyEvents.OFF, 0)));
        node.then(Commands.literal("auto").executes(ctx -> set(ctx.getSource(), effect, SkyEvents.AUTO, 0)));
        return node;
    }

    private static int set(CommandSourceStack source, SkyEvents.Effect effect, int mode, int nebulaType) {
        SkyEvents.force(effect, mode);
        if (effect == SkyEvents.Effect.NEBULA && mode == SkyEvents.ON) {
            SkyEvents.forceNebulaType(nebulaType);
        }
        String state = mode == SkyEvents.ON ? "on" : mode == SkyEvents.OFF ? "off" : "back to chance";
        StringBuilder text = new StringBuilder("Sky effect ").append(effect.id).append(": ").append(state).append('.');
        if (mode == SkyEvents.ON) {
            if (effect == SkyEvents.Effect.NEBULA) {
                int images = com.solarbiscuit.atmosphera.atmosphera.sky.NebulaImages.count();
                text.append(nebulaType > 0
                        ? " Image " + com.solarbiscuit.atmosphera.atmosphera.sky.NebulaImages.describe(nebulaType - 1) + " of " + images + "."
                        : " The night's own image (add 1-" + images + " for another).");
            }
            String needs = SkyEvents.needs(effect);
            if (needs != null) {
                text.append(" It needs ").append(needs).append('.');
            }
            if (effect == SkyEvents.Effect.MAMMATUS) {
                text.append(" It needs a low cloud deck overhead (try /weather thunder for the full look).");
            }
        }
        String message = text.toString();
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static int list(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Sky effects (/atmosphera skyeffect <name> [on|off|auto]):")
                .withStyle(ChatFormatting.GOLD), false);
        for (SkyEvents.Effect effect : SkyEvents.Effect.values()) {
            int mode = SkyEvents.forced(effect);
            float strength = SkyEvents.strength(effect);
            String line = String.format(Locale.ROOT, "  %s: %s%s", effect.id,
                    mode == SkyEvents.ON ? "forced on" : mode == SkyEvents.OFF ? "forced off" : "chance",
                    strength > 0.01f ? String.format(Locale.ROOT, ", showing %.0f%%", strength * 100f) : "");
            ChatFormatting colour = strength > 0.01f ? ChatFormatting.GREEN : mode != SkyEvents.AUTO ? ChatFormatting.YELLOW
                    : ChatFormatting.GRAY;
            source.sendSuccess(() -> Component.literal(line).withStyle(colour), false);
        }
        if (!AtmospheraConfig.events.get()) {
            source.sendSuccess(() -> Component.literal("  Sky events are off in the config (events.enabled); forced ones still show.")
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        return 1;
    }
}
