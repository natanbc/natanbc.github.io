package bench;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@State(Scope.Benchmark)
public class CanItReallyBeThatSlow {
    private static final EventDispatcher<AbsolutelyNothingHappenedEvent> EMPTY_DISPATCHER = new EventDispatcher<>();
    
    private static final EventDispatcher<AbsolutelyNothingHappenedEvent> DISPATCHER = new EventDispatcher<>();
    
    private static final MutableCallSite EMPTY_CALL_SITE = new MutableCallSite(
            MethodHandles.empty(MethodType.methodType(void.class, AbsolutelyNothingHappenedEvent.class))
    );
    private static final MethodHandle EMPTY_DISPATCH_EVENT = EMPTY_CALL_SITE.dynamicInvoker();
    
    private static final MutableCallSite EMPTY_DISPATCHER_CALL_SITE = new MutableCallSite(MethodType.methodType(void.class, AbsolutelyNothingHappenedEvent.class));
    private static final MethodHandle EMPTY_DISPATCHER_DISPATCH_EVENT = EMPTY_DISPATCHER_CALL_SITE.dynamicInvoker();
    
    private static final MutableCallSite DISPATCHER_CALL_SITE = new MutableCallSite(MethodType.methodType(void.class, AbsolutelyNothingHappenedEvent.class));
    private static final MethodHandle DISPATCHER_DISPATCH_EVENT = DISPATCHER_CALL_SITE.dynamicInvoker();
    
    private static final MHEventDispatcher MH_DISPATCHER = new MHEventDispatcher(AbsolutelyNothingHappenedEvent.class);
    private static final MethodHandle MH_DISPATCH_EVENT = MH_DISPATCHER.callSite.dynamicInvoker();
    
    static {
        DISPATCHER.add(e -> {});
        var lookup = MethodHandles.lookup();
        try {
            EMPTY_DISPATCHER_CALL_SITE.setTarget(
                    lookup
                            .bind(EMPTY_DISPATCHER, "handle", MethodType.methodType(void.class, Object.class))
                            .asType(EMPTY_DISPATCHER_CALL_SITE.type())
            );
            DISPATCHER_CALL_SITE.setTarget(
                    lookup
                            .bind(DISPATCHER, "handle", MethodType.methodType(void.class, Object.class))
                            .asType(DISPATCHER_CALL_SITE.type())
            );
            MH_DISPATCHER.add(
                    lookup
                            .findStatic(CanItReallyBeThatSlow.class, "handleEvent", MethodType.methodType(void.class, AbsolutelyNothingHappenedEvent.class))
                            .asType(MethodType.methodType(void.class, Object.class))
            );
        } catch(ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
    
    private static void handleEvent(AbsolutelyNothingHappenedEvent event) {}
    
    @Benchmark
    public void doAbsolutelyNothing() {}
    
    @Benchmark
    public void stillDoAbsolutelyNothingButMaybeNotifyAbsolutelyNoOne() {
        EMPTY_DISPATCHER.handle(new AbsolutelyNothingHappenedEvent());
    }
    
    @Benchmark
    public void doAbsolutelyNothingAgainButNowNotifySomeoneThatDoesNothing() {
        DISPATCHER.handle(new AbsolutelyNothingHappenedEvent());
    }
    
    @Benchmark
    public void doAbsolutelyNothingButMaybeNotifyAbsolutelyNoOneFaster() throws Throwable {
        EMPTY_DISPATCH_EVENT.invokeExact(new AbsolutelyNothingHappenedEvent());
    }
    
    @Benchmark
    public void doAbsolutelyNothingButSurelyItsFastRight() throws Throwable {
        EMPTY_DISPATCHER_DISPATCH_EVENT.invokeExact(new AbsolutelyNothingHappenedEvent());
    }
    
    @Benchmark
    public void doAbsolutelyNothingAndNotifyButSurelyItsFastRight() throws Throwable {
        DISPATCHER_DISPATCH_EVENT.invokeExact(new AbsolutelyNothingHappenedEvent());
    }
    
    @Benchmark
    public void doAbsolutelyNothingButWeFinallyMadeItFast() throws Throwable {
        MH_DISPATCH_EVENT.invokeExact(new AbsolutelyNothingHappenedEvent());
    }
    
    private static class AbsolutelyNothingHappenedEvent {}
    
    private static class EventDispatcher<T> {
        private final List<Consumer<T>> list = new ArrayList<>();
        void add(Consumer<T> handler) { list.add(handler); }
        void handle(T event) {
            for(var handler : list) {
                handler.accept(event);
            }
        }
    }
    
    private static class MHEventDispatcher {
        final List<MethodHandle> handlers = new ArrayList<>();
        final MutableCallSite callSite;
        
        MHEventDispatcher(Class<?> target) {
            this.callSite = new MutableCallSite(MethodHandles.empty(MethodType.methodType(void.class, target)));
        }
        
        void add(MethodHandle handle) {
            handlers.add(handle);
            rebind();
        }
        
        private void rebind() {
            if(handlers.isEmpty()) {
                callSite.setTarget(MethodHandles.empty(callSite.type()));
            } else {
                var res = MethodHandles.empty(MethodType.methodType(void.class, Object.class));
                for(var h : handlers) {
                    res = MethodHandles.foldArguments(res, h);
                }
                callSite.setTarget(res.asType(callSite.type()));
            }
        }
    }
}
