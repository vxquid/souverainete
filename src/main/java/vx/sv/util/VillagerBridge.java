package vx.sv.util;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

public abstract class VillagerBridge extends Villager {
    public VillagerBridge(EntityType<? extends Villager> type, Level level, ResourceKey<VillagerType> villagerType) {
        // Вызываем новый 2-аргументный конструктор 26.2
        super(type, level);

        // Устанавливаем тип жителя вручную через реестр
        this.setVillagerData(this.getVillagerData().withType(BuiltInRegistries.VILLAGER_TYPE.getOrThrow(villagerType)));
    }

    // Этот метод "починит" сигнатуру для Kotlin
    @Override
    public boolean equals(@Nullable Object obj) {
        return super.equals(obj);
    }
}