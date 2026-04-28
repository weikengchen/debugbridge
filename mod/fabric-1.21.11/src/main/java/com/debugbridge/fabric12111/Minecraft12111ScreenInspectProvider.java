package com.debugbridge.fabric12111;

import com.debugbridge.core.screen.ScreenInspectProvider;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class Minecraft12111ScreenInspectProvider implements ScreenInspectProvider {

    @Override
    public JsonObject inspectCurrentScreen() throws Exception {
        JsonObject out = new JsonObject();
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc.screen;
        if (screen == null) {
            out.addProperty("open", false);
            return out;
        }
        out.addProperty("open", true);
        out.addProperty("type", screen.getClass().getName());
        out.addProperty("title", screen.getTitle().getString());

        if (screen instanceof AbstractContainerScreen<?> cs) {
            AbstractContainerMenu menu = cs.getMenu();
            out.addProperty("menuClass", menu.getClass().getName());
            JsonArray slots = new JsonArray();
            for (int i = 0; i < menu.slots.size(); i++) {
                Slot slot = menu.slots.get(i);
                JsonObject s = new JsonObject();
                s.addProperty("idx", i);
                s.addProperty("container", slot.container.getClass().getName());
                ItemStack stack = slot.getItem();
                if (!stack.isEmpty()) {
                    JsonObject item = new JsonObject();
                    item.addProperty("itemId", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                    item.addProperty("count", stack.getCount());
                    if (stack.isDamageableItem()) {
                        item.addProperty("damage", stack.getDamageValue());
                        item.addProperty("maxDamage", stack.getMaxDamage());
                    }
                    if (stack.has(DataComponents.CUSTOM_NAME)) {
                        item.addProperty("name", stack.getHoverName().getString());
                    }
                    s.add("item", item);
                }
                slots.add(s);
            }
            out.add("slots", slots);
        }
        return out;
    }
}
