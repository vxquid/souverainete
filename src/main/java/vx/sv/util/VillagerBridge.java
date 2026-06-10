package vx.sv.util;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

public abstract class VillagerBridge extends Villager {
    public VillagerBridge(EntityType<? extends Villager> type, Level level, ResourceKey<VillagerType> villagerType) {
        super(type, level, villagerType);
    }
    
    // Этот метод "починит" сигнатуру для Kotlin
    @Override
    public boolean equals(@Nullable Object obj) {
        return super.equals(obj);
    }
}