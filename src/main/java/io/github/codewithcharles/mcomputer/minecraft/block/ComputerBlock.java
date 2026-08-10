package io.github.codewithcharles.mcomputer.minecraft.block;

import com.mojang.serialization.MapCodec;
import io.github.codewithcharles.mcomputer.minecraft.block.entity.ComputerBlockEntity;
import io.github.codewithcharles.mcomputer.minecraft.block.entity.MComputerBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

public final class ComputerBlock extends BaseEntityBlock {

    public static final BooleanProperty LIT = BlockStateProperties.LIT;
    public static final MapCodec<ComputerBlock> CODEC = simpleCodec(ComputerBlock::new);

    public ComputerBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(LIT, Boolean.FALSE));
    }

    @Override
    public MapCodec<ComputerBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LIT);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ComputerBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            BlockHitResult hit)
    {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (level.getBlockEntity(pos) instanceof ComputerBlockEntity computer) {
            computer.toggle();
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public <T extends BlockEntity>BlockEntityTicker<T> getTicker(
            Level level,
            BlockState state,
            BlockEntityType<T> type)
    {
        if (level.isClientSide()) {
            return null;
        }
        return createTickerHelper(type, MComputerBlockEntities.COMPUTER,
                (tickLevel, pos, tickState, computer) -> computer.serverTick());
    }
}