package com.elpinho.seekerdrones.operator;

import java.util.Set;
import java.util.UUID;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;

/**
 * One Operator Group (DESIGN.md section 6.1). The owner is always an operator but is not stored in {@code operators}.
 */
public record OperatorGroup(UUID owner, Set<UUID> operators) {
    public static final MapCodec<OperatorGroup> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("owner").forGetter(OperatorGroup::owner),
            UUIDUtil.CODEC_SET.optionalFieldOf("operators", Set.of()).forGetter(OperatorGroup::operators)
    ).apply(instance, OperatorGroup::new));

    public OperatorGroup {
        operators = Set.copyOf(operators);
    }

    public boolean isOperator(UUID player) {
        return owner.equals(player) || operators.contains(player);
    }
}
