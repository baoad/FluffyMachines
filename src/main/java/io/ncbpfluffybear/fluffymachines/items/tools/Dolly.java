package io.ncbpfluffybear.fluffymachines.items.tools;

import com.xzavier0722.mc.plugin.slimefun4.storage.util.InvStorageUtils;
import com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.ItemSetting;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.player.PlayerBackpack;
import io.github.thebusybiscuit.slimefun4.api.player.PlayerProfile;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.handlers.ItemUseHandler;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.implementation.items.SimpleSlimefunItem;
import io.github.thebusybiscuit.slimefun4.libraries.dough.collections.Pair;
import io.github.thebusybiscuit.slimefun4.libraries.dough.protection.Interaction;
import io.ncbpfluffybear.fluffymachines.utils.Utils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Chest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

public class Dolly extends SimpleSlimefunItem<ItemUseHandler> {

    private static final ItemStack LOCK_ITEM = Utils.buildNonInteractable(
            Material.DIRT, "&4&l错误", "&c你要搬到哪里?"
    );

    private ItemSetting<Boolean> canPickupLockedChest = new ItemSetting<>(this, "can-pick-locked-chest", true);

    private static final int DELAY = 500; // 500ms
    private final Map<Player, Long> timeouts;

    public Dolly(ItemGroup category, SlimefunItemStack item, RecipeType recipeType, ItemStack[] recipe) {
        super(category, item, recipeType, recipe);
        this.timeouts = new HashMap<>();
        addItemSetting(canPickupLockedChest);
    }

    @Nonnull
    @Override
    public ItemUseHandler getItemHandler() {
        return e -> {
            e.cancel();

            Player p = e.getPlayer();

            if (timeouts.containsKey(p) && timeouts.get(p) + DELAY > System.currentTimeMillis()) {
                Utils.send(p, "&c你需要等待一会才能再次使用箱子搬运车!");
                return;
            }

            timeouts.put(p, System.currentTimeMillis());

            ItemStack dolly = e.getItem();

            if (!e.getClickedBlock().isPresent()) {
                return;
            }

            Block b = e.getClickedBlock().get();

            // Block usage on Slimefun Blocks
            if (StorageCacheUtils.hasBlock(b.getLocation())) {
                return;
            }

            if (canPickupChest(b, p)) {
                // Create dolly if not already one
                ItemMeta meta = dolly.getItemMeta();
                if (!PlayerBackpack.getBackpackUUID(meta).isPresent() && !PlayerBackpack.getBackpackID(meta).isPresent()){
                    PlayerProfile.get(p, profile -> {
                        PlayerBackpack bp = Slimefun.getDatabaseManager().getProfileDataController().createBackpack(
                                p,
                                "&b箱子搬运车",
                                profile.nextBackpackNum(),
                                54
                        );
                        PlayerBackpack.bindItem(dolly, bp);
                        bp.getInventory().setItem(0, LOCK_ITEM);
                        Slimefun.getDatabaseManager().getProfileDataController().saveBackpackInventory(bp, 0);
                        if (Bukkit.isPrimaryThread()) {
                            pickupChest(dolly, bp, b, p);
                        } else {
                            Utils.runSync(() -> pickupChest(dolly, bp, b, p));
                        }
                    });
                } else {
                    PlayerBackpack.getAsync(dolly, bp -> pickupChest(dolly, bp, b, p), true);
                }
            } else if (Slimefun.getProtectionManager().hasPermission(
                    e.getPlayer(), b.getLocation(), Interaction.PLACE_BLOCK)
            ) {
                // Place new chest
                placeChest(dolly, b.getRelative(e.getClickedFace()), p);
            }
        };
    }

    private boolean canPickupChest(Block b, Player p) {
        if (b.getType() != Material.CHEST) {
            return false;
        }
        if (!canPickupLockedChest.getValue() && ((org.bukkit.block.Chest) b.getState()).isLocked()) {
            return false;
        }
        return Slimefun.getProtectionManager().hasPermission(p, b.getLocation(), Interaction.BREAK_BLOCK);
    }

    private void pickupChest(ItemStack dolly, PlayerBackpack backpack, Block chest, Player p) {
        Inventory chestInventory = ((InventoryHolder) chest.getState()).getInventory();

        // Dolly full/empty status determined by lock item in first slot
        // Make sure the dolly is empty
        if (!isLockItem(backpack.getInventory().getItem(0))) {
            Utils.send(p, "&c该箱子搬运车已经拿起了一个箱子!");
            return;
        }

        // Update old dollies to be able to store double chests
        if (backpack.getSize() < 54) {
            backpack.setSize(54);
        }

        ItemStack[] contents = chestInventory.getContents();
        List<Pair<ItemStack, Integer>> snapshot = InvStorageUtils.getInvSnapshot(backpack.getInventory().getContents());
        Set<Integer> saveSlots = InvStorageUtils.getChangedSlots(snapshot, contents);
        backpack.getInventory().setStorageContents(contents);

        boolean isDouble = false;
        // Add marker for single chests
        if (chestInventory.getSize() == 54) { // Double chest (Avoid instanceof because of weird chest class setup)
            isDouble = true;
        } else {
            backpack.getInventory().setItem(27, LOCK_ITEM);
            saveSlots.add(27);
        }
        Slimefun.getDatabaseManager().getProfileDataController().saveBackpackInventory(backpack, saveSlots);

        // Clear chest
        chestInventory.clear();
        dolly.setType(Material.CHEST_MINECART);

        // Deals with async problems
        if (isDouble) {
            DoubleChest doubleChest = (DoubleChest) ((org.bukkit.block.Chest) chest.getState()).getInventory().getHolder();

            // Set other side of chest to air
            if (((org.bukkit.block.Chest) doubleChest.getLeftSide()).getLocation().equals(chest.getLocation())
            ) {
                ((org.bukkit.block.Chest) doubleChest.getRightSide()).getLocation().getBlock().setType(Material.AIR);
            } else {
                ((org.bukkit.block.Chest) doubleChest.getLeftSide()).getLocation().getBlock().setType(Material.AIR);
            }
        }

        chest.setType(Material.AIR);
        Utils.send(p, "&a你拿起了箱子");
    }

    private void placeChest(ItemStack dolly, Block chestBlock, Player p) {
        PlayerBackpack.getAsync(dolly, backpack -> {

            if (backpack == null) {
                return;
            }

            // Update backpack size to fit doublechests
            if (backpack.getSize() == 27) {
                backpack.setSize(54);
                backpack.getInventory().setItem(27, LOCK_ITEM); // Mark as single chest
            }

            final ItemStack[][] bpContents = {backpack.getInventory().getContents()};

            if (isLockItem(bpContents[0][0])) {
                Utils.send(p, "&c你必须拿起一个箱子!");
                return;
            }

            boolean singleChest = isLockItem(bpContents[0][27]);
            if (!canChestFit(chestBlock, p, singleChest)) {
                Utils.send(p, "&c该箱子无法放置于此处!");
                return;
            }

            Utils.runSync(new BukkitRunnable() {
                @Override
                public void run() {
                    createChest(chestBlock, p, singleChest);
                    backpack.getInventory().setItem(0, LOCK_ITEM);
                    Slimefun.getDatabaseManager().getProfileDataController().saveBackpackInventory(backpack, 0);

                    // Shrink contents size if single chest
                    if (singleChest) {
                        bpContents[0] = Arrays.copyOf(bpContents[0], 27);
                    }

                    ((InventoryHolder) chestBlock.getState()).getInventory().setStorageContents(bpContents[0]);
                    Slimefun.getDatabaseManager().getProfileDataController().saveBackpackInventory(backpack, IntStream.range(0, backpack.getSize()).boxed().toArray(Integer[]::new));
                    dolly.setType(Material.MINECART);
                    Utils.send(p, "&a已放置箱子");
                }
            });
        }, false);
    }

    private boolean canChestFit(Block singleChestBlock, Player p, boolean singleChest) {

        boolean fits = singleChestBlock.getType() == Material.AIR;

        if (!singleChest) {
            fits = fits && getRightBlock(singleChestBlock, p.getFacing().getOppositeFace()).getType() == Material.AIR;
        }

        return fits;
    }

    private void createChest(Block firstChest, Player p, boolean singleChest) {
        BlockFace chestFace = p.getFacing().getOppositeFace();

        // Place chest and rotate
        firstChest.setType(Material.CHEST);
        Directional firstDirectional = ((Directional) firstChest.getBlockData());
        firstDirectional.setFacing(chestFace);
        firstChest.setBlockData(firstDirectional);

        if (!singleChest) {
            // Get block on right (Previous cardinal)
            Block secondChest = getRightBlock(firstChest, chestFace);

            // Place chest and rotate
            secondChest.setType(Material.CHEST);
            Directional secondDirectional = ((Directional) secondChest.getBlockData());
            secondDirectional.setFacing(chestFace);
            secondChest.setBlockData(secondDirectional);

            // Connect chests
            Chest firstChestType = ((Chest) firstChest.getBlockData());
            Chest secondChestType = ((Chest) secondChest.getBlockData());

            firstChestType.setType(Chest.Type.RIGHT); // Don't know why these are flipped
            secondChestType.setType(Chest.Type.LEFT);

            firstChest.setBlockData(firstChestType);
            secondChest.setBlockData(secondChestType);
        }
    }

    @Nonnull
    private Block getRightBlock(Block b, BlockFace face) {

        BlockFace rightFace;

        switch (face) {
            case NORTH:
                rightFace = BlockFace.WEST;
                break;
            case EAST:
                rightFace = BlockFace.NORTH;
                break;
            case SOUTH:
                rightFace = BlockFace.EAST;
                break;
            case WEST:
                rightFace = BlockFace.SOUTH;
                break;
            default:
                throw new IllegalStateException("Unexpected value: " + face);
        }

        return b.getRelative(rightFace);

    }

    private boolean isLockItem(@Nullable ItemStack lockItem) {
        return lockItem != null && (Utils.checkNonInteractable(lockItem)
                || lockItem.getItemMeta().hasCustomModelData() // Remnants of when I didn't know what PDC was
                && lockItem.getItemMeta().getCustomModelData() == 6969); // Leave in to maintain compatibility
    }

}
