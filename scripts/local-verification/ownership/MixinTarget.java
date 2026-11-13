package local.hyinit.ownership.mixin;
import local.hyinit.ownership.shared.Access;
import local.hyinit.ownership.Target;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(Target.class)
public interface MixinTarget extends Access {
    @Accessor("value") int value();
}
