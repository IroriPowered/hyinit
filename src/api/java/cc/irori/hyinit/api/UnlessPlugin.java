package cc.irori.hyinit.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@Repeatable(UnlessPlugin.List.class)
public @interface UnlessPlugin {
    String value();

    String version() default "*";

    @Retention(RetentionPolicy.CLASS)
    @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
    @interface List {
        UnlessPlugin[] value();
    }
}
