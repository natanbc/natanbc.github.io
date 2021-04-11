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
public class Bonus {
    private static final EventDispatcher<EventWithVal> DISPATCHER = new EventDispatcher<>();
    
    private static final MHEventDispatcher MH_DISPATCHER = new MHEventDispatcher(EventWithVal.class);
    private static final MethodHandle MH_DISPATCH_EVENT = MH_DISPATCHER.callSite.dynamicInvoker();
    private static final MethodHandle MH_DISPATCH_EVENT_OBJ = MH_DISPATCH_EVENT.asType(MethodType.methodType(void.class, Object.class));
    
    static {
        DISPATCHER.add(e -> {});
        var lookup = MethodHandles.lookup();
        try {
            MH_DISPATCHER.add(
                    lookup
                            .findStatic(Bonus.class, "handler1", MethodType.methodType(void.class, EventWithVal.class))
                            .asType(MethodType.methodType(void.class, Object.class))
            );
            MH_DISPATCHER.add(
                    lookup
                            .findStatic(Bonus.class, "handler2", MethodType.methodType(void.class, EventWithVal.class))
                            .asType(MethodType.methodType(void.class, Object.class))
            );
        } catch(ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
    
    private static void handler1(EventWithVal event) {
        event.val++;
    }
    
    private static void handler2(EventWithVal event) {
        event.val += 2;
    }
    
    @Benchmark
    public int baseline() {
        var event = new EventWithVal(1);
        return event.val;
    }
    
    @Benchmark
    public int regularDispatcher() {
        var event = new EventWithVal(1);
        DISPATCHER.handle(event);
        return event.val;
    }
    
    @Benchmark
    public int methodHandleExact() throws Throwable {
        var event = new EventWithVal(1);
        MH_DISPATCH_EVENT.invokeExact(event);
        return event.val;
    }
    
    @Benchmark
    public int methodHandleGeneric() throws Throwable {
        var event = new EventWithVal(1);
        //emulate a generic dispatch(T) method
        MH_DISPATCH_EVENT.invoke((Object)event);
        return event.val;
    }
    
    @Benchmark
    public int methodHandleExactAsType() throws Throwable {
        var event = new EventWithVal(1);
        //emulate a generic dispatch(T) method
        MH_DISPATCH_EVENT_OBJ.invokeExact((Object)event);
        return event.val;
    }
    
    @Benchmark
    public int directCalls() {
        var event = new EventWithVal(1);
        handler1(event);
        handler2(event);
        return event.val;
    }
    
    private static class EventWithVal {
        int val;
        
        EventWithVal(int i) { val = i; }
    }
    
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
            MutableCallSite.syncAll(new MutableCallSite[] { callSite });
        }
    }
}
