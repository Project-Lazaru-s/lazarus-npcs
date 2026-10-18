package com.lazarus.npcs;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.UUID;

@Mod.EventBusSubscriber(modid = LazarusNpcs.ID, value = Dist.CLIENT)
public final class NpcClient {
    private static final ResourceLocation PANEL = new ResourceLocation(LazarusNpcs.ID, "textures/gui/journal.png");
    private static UUID lastNpc;
    private static int lastTab;
    private static int guardRadius = 1;
    private static int journalTab;

    private NpcClient() {}

    @SubscribeEvent
    public static void inventoryButton(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof InventoryScreen)) return;
        int left = (event.getScreen().width - 176) / 2;
        int top = (event.getScreen().height - 166) / 2;
        event.addListener(Button.builder(Component.empty(), button -> NpcNetwork.send("journal", new UUID(0, 0)))
                .bounds(left + 74, top + 27, 18, 18)
                .tooltip(Tooltip.create(Component.literal("Survivor journal"))).build());
    }

    @SubscribeEvent
    public static void inventoryIcon(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof InventoryScreen)) return;
        int left = (event.getScreen().width - 176) / 2;
        int top = (event.getScreen().height - 166) / 2;
        event.getGuiGraphics().renderItem(Items.WRITABLE_BOOK.getDefaultInstance(), left + 75, top + 28);
    }

    static void dialogue(NpcNetwork.DialogueView view) {
        Minecraft minecraft = Minecraft.getInstance();
        if (view.close()) {
            if (minecraft.screen instanceof DialogueScreen) minecraft.setScreen(null);
            return;
        }
        minecraft.setScreen(new DialogueScreen(view));
    }

    static void open(NpcNetwork.View view) {
        if ("journal".equals(view.page())) Minecraft.getInstance().setScreen(new JournalScreen(view));
        else if ("encounter".equals(view.page())) Minecraft.getInstance().setScreen(new EncounterScreen(view));
        else if ("surrender".equals(view.page())) Minecraft.getInstance().setScreen(new SurrenderScreen(view));
        else {
            if (!view.npc().equals(lastNpc)) lastTab = 0;
            lastNpc = view.npc();
            Minecraft.getInstance().setScreen(new NpcScreen(view));
        }
    }

    private static final class EncounterScreen extends PaperScreen {
        private final NpcNetwork.View view;
        EncounterScreen(NpcNetwork.View view) { super("Human Encounter"); this.view = view; }

        @Override protected void init() {
            addRenderableWidget(Button.builder(Component.literal("Offer supplies"), button -> {
                NpcNetwork.send("tribute", view.npc());
                onClose();
            }).bounds(left() + 35, top() + 127, 90, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Leave"), button -> onClose())
                    .bounds(left() + 132, top() + 127, 90, 20).build());
        }

        @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            super.render(graphics, mouseX, mouseY, partialTick);
            label(graphics, "LAZARUS / ENCOUNTER", 18, 14, 0xFFE4D9C8);
            label(graphics, view.name(), 20, 49, 0xFFD4A185);
            wrapped(graphics, "They demand " + view.service() + " to let you pass. You can leave the area instead. Attacking will provoke the group.",
                    20, 72, 212, 0xFFE3D9C9, 4);
        }
    }

    private static final class SurrenderScreen extends PaperScreen {
        private final NpcNetwork.View view;
        SurrenderScreen(NpcNetwork.View view) { super("Surrender"); this.view = view; }

        @Override protected void init() {
            addRenderableWidget(Button.builder(Component.literal("Take supplies"), button -> {
                NpcNetwork.send("strip", view.npc());
                onClose();
            }).bounds(left() + 35, top() + 140, 90, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Let them go"), button -> {
                NpcNetwork.send("letgo", view.npc());
                onClose();
            }).bounds(left() + 132, top() + 140, 90, 20).build());
        }

        @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            super.render(graphics, mouseX, mouseY, partialTick);
            label(graphics, "LAZARUS / SURRENDER", 18, 14, 0xFFE4D9C8);
            label(graphics, "soldier".equals(view.role()) ? "Rogue soldier" : "Looter", 20, 49, 0xFFD4A185);
            wrapped(graphics, "They dropped their weapon and wait for one minute. Take their supplies ("
                    + ("soldier".equals(view.role()) ? "1 canned food and 2 iron ingots" : "1 canned food, maybe a bandage")
                    + ") or let them go; either way they leave. A prisoner killed now drops nothing and costs Wardens trust.",
                    20, 72, 212, 0xFFE3D9C9, 5);
        }
    }

    /** A line from the NPC and up to four answers, all validated again by the server when picked. */
    private static final class DialogueScreen extends PaperScreen {
        private final NpcNetwork.DialogueView view;
        private final Component line;

        DialogueScreen(NpcNetwork.DialogueView view) {
            super("Conversation");
            this.view = view;
            this.line = parse(view.lineJson());
        }

        private static Component parse(String json) {
            try {
                Component component = Component.Serializer.fromJson(json);
                return component == null ? Component.empty() : component;
            } catch (RuntimeException malformed) {
                return Component.literal(json);
            }
        }

        @Override protected void init() {
            addRenderableWidget(Button.builder(Component.literal("Back"), button -> NpcNetwork.choose(view.npc(), view.token(), -1))
                    .bounds(left() + 202, top() + 8, 42, 16).build());
            for (int i = 0; i < view.labelsJson().size(); i++) {
                final int index = i;
                addRenderableWidget(Button.builder(parse(view.labelsJson().get(i)), button -> {
                    NpcNetwork.choose(view.npc(), view.token(), index);
                    button.active = false;
                }).bounds(left() + 28, top() + 104 + i * 22, 200, 20).build());
            }
        }

        @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            super.render(graphics, mouseX, mouseY, partialTick);
            label(graphics, "LAZARUS / CONVERSATION", 17, 13, 0xFFE4D9C8);
            label(graphics, view.name(), 20, 36, factionColor(view.faction()));
            int row = 0;
            for (var text : font.split(line, 214)) {
                if (row >= 4) break;
                graphics.drawString(font, text, left() + 20, top() + 52 + row++ * 12, 0xFFEEE7DB, false);
            }
        }

        private static int factionColor(String faction) {
            return 0xFF000000 | switch (faction) {
                case "haven" -> 0x8FCB91;
                case "rovers" -> 0xE0AE67;
                case "wardens" -> 0x84B5D6;
                case "military" -> 0xB5C48B;
                case "hostile" -> 0xD48968;
                default -> 0xD8C6AA;
            };
        }
    }

    private static abstract class PaperScreen extends Screen {
        PaperScreen(String title) { super(Component.literal(title)); }
        int left() { return (width - 256) / 2; }
        int top() { return (height - 202) / 2; }

        @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            renderBackground(graphics);
            graphics.blit(PANEL, left(), top(), 0, 0, 256, 202, 256, 202);
            super.render(graphics, mouseX, mouseY, partialTick);
        }

        void label(GuiGraphics graphics, String value, int x, int y, int color) {
            graphics.drawString(font, value, left() + x, top() + y, color, false);
        }

        void wrapped(GuiGraphics graphics, String value, int x, int y, int width, int color) {
            wrapped(graphics, value, x, y, width, color, 7);
        }

        void wrapped(GuiGraphics graphics, String value, int x, int y, int width, int color, int maxLines) {
            int line = 0;
            for (var text : font.split(Component.literal(value), width)) {
                graphics.drawString(font, text, left() + x, top() + y + line * 12, color, false);
                line++;
                if (line >= maxLines) break;
            }
        }

        @Override public boolean isPauseScreen() { return false; }
    }

    private static final class JournalScreen extends PaperScreen {
        private final NpcNetwork.View view;
        JournalScreen(NpcNetwork.View view) { super("Survivor Journal"); this.view = view; }

        @Override protected void init() {
            addRenderableWidget(Button.builder(Component.literal("Back"), button -> onClose())
                    .bounds(left() + 202, top() + 8, 42, 16).build());
            addRenderableWidget(Button.builder(Component.literal(switch (journalTab) {
                case 1 -> "Contracts";
                case 2 -> "Stories";
                case 3 -> "Encounters";
                default -> "Task";
            }), button -> {
                journalTab = (journalTab + 1) % 4;
                rebuildWidgets();
            }).bounds(left() + 138, top() + 8, 60, 16).build());
            if (journalTab == 1) {
                if (!view.contract().startsWith("No active"))
                    addRenderableWidget(Button.builder(Component.literal("Release all contracts"), button ->
                            NpcNetwork.send("releaseall", new UUID(0, 0)))
                            .bounds(left() + 60, top() + 168, 136, 18).build());
            } else if (journalTab == 3) {
                String first = view.encounter().lines().findFirst().orElse("");
                boolean finished = first.endsWith("(completed)") || first.endsWith("(expired)")
                        || first.endsWith("(abandoned)") || first.contains("(failed");
                if (!view.encounter().isEmpty() && !finished)
                    addRenderableWidget(Button.builder(Component.literal("Abandon encounter task"), button ->
                            NpcNetwork.send("encabandon", new UUID(0, 0)))
                            .bounds(left() + 60, top() + 172, 136, 16).build());
            } else if (journalTab == 0 && !"No active task".equals(view.task()))
                addRenderableWidget(Button.builder(Component.literal("Abandon"), button ->
                        NpcNetwork.send("abandon", new UUID(0, 0)))
                        .bounds(left() + 178, top() + 95, 60, 16).build());
        }

        @Override public void onClose() {
            if (minecraft != null && minecraft.player != null)
                minecraft.setScreen(new InventoryScreen(minecraft.player));
        }

        @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            super.render(graphics, mouseX, mouseY, partialTick);
            label(graphics, "LAZARUS / JOURNAL", 17, 13, 0xFFE4D9C8);
            if (journalTab == 3) {
                label(graphics, "ENCOUNTER TASK", 20, 39, 0xFFD4A185);
                if (view.encounter().isEmpty()) {
                    wrapped(graphics, "No encounter task. Survivors in trouble may ask for your help.", 20, 55, 215, 0xFFB9A995, 3);
                    return;
                }
                int y = 55;
                String[] lines = view.encounter().lines().toArray(String[]::new);
                for (int i = 0; i < lines.length && y < 166; i++) {
                    int rows = Math.min(i == 1 ? 3 : 2, font.split(Component.literal(lines[i]), 215).size());
                    wrapped(graphics, lines[i], 20, y, 215, i == 0 ? 0xFFF4ECE0 : i == lines.length - 1 ? 0xFFD4A185 : 0xFFE3D9C9, rows);
                    y += 12 * rows + 3;
                }
                return;
            }
            if (journalTab == 2) {
                label(graphics, "DISCOVERED STORIES", 20, 39, 0xFFD4A185);
                int y = 56;
                for (String story : view.stories().split("\\n")) {
                    wrapped(graphics, story, 20, y, 215, 0xFFF1EADD, 2);
                    y += 26;
                    if (y > 174) break;
                }
                return;
            }
            if (journalTab == 1) {
                label(graphics, "ESCORTS AND GUARDS", 20, 39, 0xFFD4A185);
                int y = 55;
                for (String line : view.contract().split(" \\| ")) {
                    wrapped(graphics, line, 20, y, 215, 0xFFF1EADD, 3);
                    y += 12 * Math.min(3, font.split(Component.literal(line), 215).size()) + 4;
                }
                return;
            }
            label(graphics, "CURRENT TASK", 20, 39, 0xFFD4A185);
            wrapped(graphics, view.task(), 20, 55, 215, 0xFFF1EADD, 3);
            String[] where = view.taskLocation().split("\n");
            for (int i = 0; i < where.length && !where[0].isEmpty(); i++)
                label(graphics, font.plainSubstrByWidth(where[i], 152), 20, 93 + i * 11, i == 0 ? 0xFFD4A185 : 0xFFB9A995);
            graphics.fill(left() + 18, top() + 117, left() + 238, top() + 118, 0xFF7B473A);
            label(graphics, "FACTION REPUTATION", 20, 124, 0xFFD4A185);
            for (int i = 0; i < NpcIdentity.FACTIONS.length; i++) {
                String name = NpcIdentity.label(NpcIdentity.FACTIONS[i]);
                label(graphics, name, 20, 140 + i * 13, 0xFFE3D9C9);
                String score = (NpcHostility.isEnemyStanding(view.reputation()[i]) ? "ENEMY  " : "") + view.reputation()[i] + "/100";
                label(graphics, score, 230 - font.width(score), 140 + i * 13, 0xFFFFFFFF);
            }
        }
    }

    private static final class NpcScreen extends PaperScreen {
        private final NpcNetwork.View view;
        NpcScreen(NpcNetwork.View view) { super("NPC Interaction"); this.view = view; }

        @Override protected void init() {
            addRenderableWidget(Button.builder(Component.literal("Close"), button -> onClose())
                    .bounds(left() + 199, top() + 8, 48, 16).build());
            String[] tabs = view.contract().isEmpty() ? new String[]{"Trade", "Services", "Tasks"}
                    : new String[]{"Trade", "Services", "Tasks", "Guard"};
            if (lastTab >= tabs.length) lastTab = 0;
            int width = tabs.length == 4 ? 52 : 69, step = tabs.length == 4 ? 56 : 74;
            for (int i = 0; i < tabs.length; i++) {
                final int selected = i;
                addRenderableWidget(Button.builder(Component.literal(tabs[i]), button -> {
                    lastTab = selected;
                    rebuildWidgets();
                }).bounds(left() + 17 + i * step, top() + 47, width, 18).build());
            }
            if (lastTab == 3) {
                int radius = NpcContracts.GUARD_RADII[guardRadius];
                addRenderableWidget(Button.builder(Component.literal("Area: " + radius + " blocks"), button -> {
                    guardRadius = (guardRadius + 1) % NpcContracts.GUARD_RADII.length;
                    rebuildWidgets();
                }).bounds(left() + 21, top() + 132, 104, 18).build());
                boolean mine = view.contract().startsWith("On duty") || view.contract().startsWith("Walking");
                addRenderableWidget(Button.builder(Component.literal(mine ? "Renew (+1 day)" : "Guard here"),
                        button -> action("guard:" + radius)).bounds(left() + 131, top() + 132, 104, 18).build());
                if (mine || view.contract().startsWith("Escorting"))
                    addRenderableWidget(Button.builder(Component.literal("Dismiss"), button -> action("dismiss"))
                            .bounds(left() + 131, top() + 153, 104, 18).build());
                return;
            }
            if (lastTab == 0) {
                addRenderableWidget(Button.builder(Component.literal("Open trade offers"), button -> action("trade"))
                        .bounds(left() + 42, top() + 105, 172, 20).build());
            } else if (lastTab == 1) {
                String role = view.role();
                String service = "survivor".equals(role) ? "repair" : "medic".equals(role) ? "stabilize" : "hunter".equals(role) ? "scout" : "escort";
                addRenderableWidget(Button.builder(Component.literal("Request " + service), button -> action(service))
                        .bounds(left() + 42, top() + 123, 172, 20).build());
            } else {
                addRenderableWidget(Button.builder(Component.literal("Accept / turn in task"), button -> action("task"))
                        .bounds(left() + 42, top() + 154, 172, 20).build());
            }
        }

        private void action(String kind) { NpcNetwork.send(kind, view.npc()); }

        @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            super.render(graphics, mouseX, mouseY, partialTick);
            label(graphics, view.name(), 17, 14, 0xFFF4ECE0);
            label(graphics, NpcIdentity.label(view.faction()), 18, 31, 0xFFD4A185);
            if (lastTab == 0) {
                wrapped(graphics, "Inspect this survivor's current stock and negotiate using the established trade menu.", 21, 76, 211, 0xFFE3D9C9);
            } else if (lastTab == 1) {
                String service = switch (view.role()) {
                    case "survivor" -> "Repair 25% durability. Cost: 3 iron ingots. Cooldown: 5 min.";
                    case "medic" -> "Heal 4 hearts and clear poison/weakness. Cost: 2 bandages + 1 canned water.";
                    case "hunter" -> "Scout infected and untouched supplies. Cost: 1 canned food. Cooldown: 3 min.";
                    default -> "Escort for 5 min: follows a few steps behind and defends you. Cost: 3 canned food + 2 iron ingots.";
                };
                wrapped(graphics, service, 21, 77, 213, 0xFFE3D9C9);
                wrapped(graphics, view.service(), 21, 150, 213, 0xFFD4A185);
            } else if (lastTab == 3) {
                wrapped(graphics, "Guards hold the spot where you stand now and fight threats inside the chosen area. Only you can renew, change or dismiss them.",
                        21, 70, 213, 0xFFE3D9C9, 3);
                wrapped(graphics, view.contract(), 21, 107, 213, 0xFFD4A185, 2);
            } else {
                label(graphics, "TASK / REWARD", 21, 77, 0xFFD4A185);
                wrapped(graphics, view.task(), 21, 94, 213, 0xFFE3D9C9, 4);
            }
            label(graphics, "Standing: " + ("independent".equals(view.faction()) ? "Neutral"
                    : view.reputation()[java.util.Arrays.asList(NpcIdentity.FACTIONS).indexOf(view.faction())] + "/100"),
                    21, 181, 0xFFB9A995);
        }
    }
}
