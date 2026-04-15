package com.debugbridge.fabric119;

import com.debugbridge.core.texture.ItemTextureProvider;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.material.MaterialColor;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Resolves item textures in Minecraft 1.19 by walking the baked item model
 * and extracting pixels from the resolved sprite in the texture atlas.
 * <p>
 * This captures the correct texture for the item including damage-based model
 * overrides and resource pack textures. Does not render overlays like
 * enchantment glint or damage bars — those are drawn by the GUI layer.
 */
public class Minecraft119ItemTextureProvider implements ItemTextureProvider {
    private static final Logger LOG = Logger.getLogger("DebugBridge");

    // Reflection cache: TextureAtlasSprite → NativeImage[] field
    private static volatile Field spritePixelsField;

    @Override
    public TextureResult getItemTexture(int slot) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        return renderStack(() -> {
            if (mc.player == null) throw new Exception("Player not available");
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack.isEmpty()) throw new Exception("Slot " + slot + " is empty");
            return stack;
        });
    }

    @Override
    public TextureResult getItemTextureById(String itemId) throws Exception {
        return renderStack(() -> {
            ResourceLocation key;
            try {
                key = new ResourceLocation(itemId);
            } catch (Exception e) {
                throw new Exception("Invalid item id: " + itemId);
            }
            if (!Registry.ITEM.containsKey(key))
                throw new Exception("Unknown item: " + itemId);
            Item item = Registry.ITEM.get(key);
            return new ItemStack(item);
        });
    }

    @Override
    public TextureResult getEntityItemTexture(int entityId, String slotName) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        return renderStack(() -> {
            if (mc.level == null) throw new Exception("Level not loaded");

            Entity target = null;
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e.getId() == entityId) { target = e; break; }
            }
            if (target == null) throw new Exception("Entity " + entityId + " not found");

            ItemStack stack;
            if ("FRAME".equals(slotName) && target instanceof ItemFrame frame) {
                stack = frame.getItem();
            } else if (target instanceof LivingEntity living) {
                EquipmentSlot slot;
                try {
                    slot = EquipmentSlot.valueOf(slotName);
                } catch (IllegalArgumentException e) {
                    throw new Exception("Unknown slot " + slotName);
                }
                stack = living.getItemBySlot(slot);
            } else {
                throw new Exception("Entity " + entityId + " has no equipment");
            }

            if (stack.isEmpty())
                throw new Exception("Slot " + slotName + " is empty on entity " + entityId);
            return stack;
        });
    }

    private TextureResult renderStack(StackSupplier supplier) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        CompletableFuture<TextureResult> future = new CompletableFuture<>();

        mc.execute(() -> {
            try {
                ItemStack stack = supplier.get();

                // Fast path: filled_map renders the actual map content (128x128
                // pixel grid via MaterialColor palette), not the inventory icon.
                TextureResult mapResult = tryRenderFilledMap(mc, stack);
                if (mapResult != null) {
                    future.complete(mapResult);
                    return;
                }

                BakedModel model = mc.getItemRenderer().getModel(stack, mc.level, mc.player, 0);

                TextureAtlasSprite sprite = findPrimarySprite(model);
                if (sprite == null) {
                    future.completeExceptionally(new Exception("No sprite found for item"));
                    return;
                }

                NativeImage img = getSpriteMainImage(sprite);
                if (img == null) {
                    future.completeExceptionally(new Exception("Sprite has no pixel data"));
                    return;
                }

                int w = img.getWidth();
                int h = img.getHeight();
                BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int abgr = img.getPixelRGBA(x, y);
                        int a = (abgr >> 24) & 0xFF;
                        int b = (abgr >> 16) & 0xFF;
                        int g = (abgr >> 8) & 0xFF;
                        int r = abgr & 0xFF;
                        bi.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                    }
                }

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(bi, "png", baos);
                String base64 = Base64.getEncoder().encodeToString(baos.toByteArray());

                future.complete(new TextureResult(base64, w, h, getSpriteName(sprite)));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        return future.get(10, TimeUnit.SECONDS);
    }

    @FunctionalInterface
    private interface StackSupplier {
        ItemStack get() throws Exception;
    }

    // ---- Filled-map rendering (bypasses the baked model pipeline) ----

    private static final int MAP_SIZE = 128;
    private static final int[] BRIGHTNESS_MOD = { 180, 220, 255, 135 };

    private TextureResult tryRenderFilledMap(Minecraft mc, ItemStack stack) throws Exception {
        if (!stack.is(Items.FILLED_MAP)) return null;
        if (mc.level == null) return null;

        MapItemSavedData mapData = MapItem.getSavedData(stack, mc.level);
        if (mapData == null || mapData.colors == null
                || mapData.colors.length < MAP_SIZE * MAP_SIZE) {
            return null;
        }

        BufferedImage img = new BufferedImage(MAP_SIZE, MAP_SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < MAP_SIZE; y++) {
            for (int x = 0; x < MAP_SIZE; x++) {
                img.setRGB(x, y, mapPixelArgb(mapData.colors[x + y * MAP_SIZE]));
            }
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        String base64 = Base64.getEncoder().encodeToString(baos.toByteArray());
        return new TextureResult(base64, MAP_SIZE, MAP_SIZE, "filled_map");
    }

    private static int mapPixelArgb(byte packedColor) {
        int colorId = (packedColor & 0xFF) >> 2;
        int shade = packedColor & 3;
        if (colorId == 0) return 0;
        MaterialColor color = MaterialColor.byId(colorId);
        if (color == null) return 0;
        int col = color.col;
        int modifier = BRIGHTNESS_MOD[shade];
        int r = ((col >> 16) & 255) * modifier / 255;
        int g = ((col >> 8) & 255) * modifier / 255;
        int b = (col & 255) * modifier / 255;
        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * Find the primary sprite for a baked model. For flat (generated) item
     * models, the quads on the SOUTH face are the item's front texture. For
     * block models, fall back to the particle icon.
     */
    private TextureAtlasSprite findPrimarySprite(BakedModel model) {
        // Try SOUTH face quads (the flat front for item icons)
        List<BakedQuad> quads = model.getQuads(null, Direction.SOUTH, null);
        if (!quads.isEmpty()) {
            return quads.get(0).getSprite();
        }

        // Try unculled quads (some block-style items)
        quads = model.getQuads(null, null, null);
        if (!quads.isEmpty()) {
            return quads.get(0).getSprite();
        }

        // Fall back to particle icon
        return model.getParticleIcon();
    }

    /**
     * Extract the full-resolution NativeImage from a TextureAtlasSprite using
     * reflection. In 1.19 the pixel data lives in a NativeImage[] field
     * (mipmap pyramid) — we take level 0.
     */
    private NativeImage getSpriteMainImage(TextureAtlasSprite sprite) throws Exception {
        if (spritePixelsField == null) {
            synchronized (Minecraft119ItemTextureProvider.class) {
                if (spritePixelsField == null) {
                    // Search the sprite's contents() object since 1.19.3+ moved pixel
                    // storage to SpriteContents. If that doesn't exist, fall back to
                    // searching the sprite itself.
                    Object contents = null;
                    try {
                        contents = sprite.getClass().getMethod("contents").invoke(sprite);
                    } catch (NoSuchMethodException ignored) {
                        // Pre-1.19.3: no SpriteContents, pixels live on sprite itself
                    }

                    Class<?> searchClass = (contents != null) ? contents.getClass() : sprite.getClass();
                    Field found = findNativeImageArrayField(searchClass);
                    if (found == null && contents != null) {
                        // Try sprite class as a backup
                        found = findNativeImageArrayField(sprite.getClass());
                    }
                    if (found == null) {
                        throw new Exception("Cannot locate NativeImage[] field on sprite");
                    }
                    found.setAccessible(true);
                    spritePixelsField = found;
                    LOG.info("[DebugBridge] Item texture provider reflection initialized: " + found);
                }
            }
        }

        // Resolve which object holds the field (contents vs sprite itself)
        Object target;
        if (spritePixelsField.getDeclaringClass().isInstance(sprite)) {
            target = sprite;
        } else {
            // Must be on contents()
            target = sprite.getClass().getMethod("contents").invoke(sprite);
        }

        NativeImage[] mipmaps = (NativeImage[]) spritePixelsField.get(target);
        if (mipmaps == null || mipmaps.length == 0) return null;
        return mipmaps[0];
    }

    private String getSpriteName(TextureAtlasSprite sprite) {
        // 1.19.3+: sprite.contents().name()
        try {
            Object contents = sprite.getClass().getMethod("contents").invoke(sprite);
            if (contents != null) {
                Object name = contents.getClass().getMethod("name").invoke(contents);
                if (name != null) return name.toString();
            }
        } catch (Exception ignored) {
        }
        // 1.19.0-1.19.2: sprite.getName()
        try {
            Object name = sprite.getClass().getMethod("getName").invoke(sprite);
            if (name != null) return name.toString();
        } catch (Exception ignored) {
        }
        return "";
    }

    private static Field findNativeImageArrayField(Class<?> cls) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == NativeImage[].class) {
                    return f;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }
}
