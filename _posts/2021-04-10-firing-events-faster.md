Reducing the cost of firing events to effectively zero, while still being able to change the listeners dynamically ([but not for free](#downsides)).

While I'll try to not make this post too complicated, it'll touch many JVM internals, so [here's some good material](https://shipilev.net/jvm/anatomy-quarks/).

## First test

Let's start by doing absolutely nothing

```java
public class Demo1 {
    public static void main(String[] args) {
        for(int i = 0; i < 1_000_000; i++) {
            doAbsolutelyNothing();
        }
    }
    
    private static void doAbsolutelyNothing() {
    
    }
}
```

Let's run it and dump the generated machine code (
`java -XX:+UnlockDiagnosticVMOptions -XX:PrintAssemblyOptions=intel -XX:CompileCommand=dontinline,Demo1.doAbsolutelyNothing -XX:CompileCommand=print,Demo1.doAbsolutelyNothing Demo1`)

```
[Verified Entry Point]
  # {method} {0x00007f4522c002e8} 'doAbsolutelyNothing' '()V' in 'Demo1'
  #           [sp+0x20]  (sp of caller)
  0x00007f454819b280:   sub    rsp,0x18
  0x00007f454819b287:   mov    QWORD PTR [rsp+0x10],rbp     ;*synchronization entry
                                                            ; - Demo1::doAbsolutelyNothing@-1 (line 10)
  0x00007f454819b28c:   add    rsp,0x10
  0x00007f454819b290:   pop    rbp
  0x00007f454819b291:   cmp    rsp,QWORD PTR [r15+0x118]    ;   {poll_return}
  0x00007f454819b298:   ja     0x00007f454819b29f
  0x00007f454819b29e:   ret
  0x00007f454819b29f:   movabs r10,0x7f454819b291           ;   {internal_word}
  0x00007f454819b2a9:   mov    QWORD PTR [r15+0x3b0],r10
  0x00007f454819b2b0:   jmp    0x00007f454071d000           ;   {runtime_call SafepointBlob}
```

What if we wanted to do something when we do absolutely nothing?

## A first attempt

Let's now fire some events, indicating that we just did absolutely nothing, with a very simple event bus

```java
public class Demo2 {
    private static final EventDispatcher<AbsolutelyNothingHappenedEvent> DISPATCHER = new EventDispatcher<>();
    
    public static void main(String[] args) {
        for(int i = 0; i < 1_000_000; i++) {
            doAbsolutelyNothing();
        }
    }
    
    private static void stillDoAbsolutelyNothingButMaybeNotifyAbsolutelyNoOne() {
        DISPATCHER.handle(new AbsolutelyNothingHappenedEvent());
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
}
```

Now we have (with some code for actually calling `handler.accept` removed because we're notifying absolutely no one):

```
[Verified Entry Point]
  # {method} {0x00007fd8330003d0} 'stillDoAbsolutelyNothingButMaybeNotifyAbsolutelyNoOne' '()V' in 'Demo2'
  #           [sp+0x30]  (sp of caller)
  0x00007fd85819b720:   mov    DWORD PTR [rsp-0x14000],eax
  0x00007fd85819b727:   push   rbp
  0x00007fd85819b728:   sub    rsp,0x20                     ;*synchronization entry
                                                            ; - Demo2::stillDoAbsolutelyNothingButMaybeNotifyAbsolutelyNoOne@-1 (line 15)
  0x00007fd85819b72c:   movabs r10,0x69f844018              ;   {oop(a 'Demo2$EventDispatcher'{0x000000069f844018})}
  0x00007fd85819b736:   mov    r11d,DWORD PTR [r10+0xc]     ;*getfield list {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - Demo2$EventDispatcher::handle@1 (line 24)
                                                            ; - Demo2::stillDoAbsolutelyNothingButMaybeNotifyAbsolutelyNoOne@10 (line 15)
  0x00007fd85819b73a:   mov    r10d,DWORD PTR [r12+r11*8+0x8]; implicit exception: dispatches to 0x00007fd85819b790
  0x00007fd85819b73f:   cmp    r10d,0x2031                  ;   {metadata('java/util/ArrayList')}
  0x00007fd85819b746:   jne    0x00007fd85819b768
  0x00007fd85819b748:   lea    r10,[r12+r11*8]              ;*invokeinterface iterator {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - Demo2$EventDispatcher::handle@4 (line 24)
                                                            ; - Demo2::stillDoAbsolutelyNothingButMaybeNotifyAbsolutelyNoOne@10 (line 15)
  0x00007fd85819b74c:   mov    r11d,DWORD PTR [r10+0x10]    ;*getfield size {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - java.util.ArrayList$Itr::hasNext@8 (line 962)
                                                            ; - Demo2$EventDispatcher::handle@11 (line 24)
                                                            ; - Demo2::stillDoAbsolutelyNothingButMaybeNotifyAbsolutelyNoOne@10 (line 15)
  0x00007fd85819b750:   test   r11d,r11d
  0x00007fd85819b753:   jne    0x00007fd85819b778           ;*if_icmpeq {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - java.util.ArrayList$Itr::hasNext@11 (line 962)
                                                            ; - Demo2$EventDispatcher::handle@11 (line 24)
                                                            ; - Demo2::stillDoAbsolutelyNothingButMaybeNotifyAbsolutelyNoOne@10 (line 15)
  0x00007fd85819b755:   add    rsp,0x20
  0x00007fd85819b759:   pop    rbp
  0x00007fd85819b75a:   cmp    rsp,QWORD PTR [r15+0x118]    ;   {poll_return}
  0x00007fd85819b761:   ja     0x00007fd85819b79c
  0x00007fd85819b767:   ret
```

That's a lot of work! But it surely can't be *that* slow, right?

```
Benchmark                                                                     Mode  Cnt           Score           Error  Units
CanItReallyBeThatSlow.doAbsolutelyNothing                                    thrpt    6  3785088934.978 ± 112415907.564  ops/s
CanItReallyBeThatSlow.stillDoAbsolutelyNothingButMaybeNotifyAbsolutelyNoOne  thrpt    6  1837413146.690 ±  42795688.853  ops/s 
```

Ouch! Now we can do absolutely nothing at only half the speed! What if we add a listener that does absolutely nothing then?

```
Benchmark                                                                          Mode  Cnt           Score           Error  Units
CanItReallyBeThatSlow.doAbsolutelyNothing                                         thrpt    6  3795694172.170 ± 253499736.745  ops/s
CanItReallyBeThatSlow.doAbsolutelyNothingAgainButNowNotifySomeoneThatDoesNothing  thrpt    6   411905505.905 ±   6940058.839  ops/s
CanItReallyBeThatSlow.stillDoAbsolutelyNothingButMaybeNotifyAbsolutelyNoOne       thrpt    6  1833128762.113 ±  44525139.385  ops/s
```

Now we do absolutely nothing in 1/9th of the speed!

## Can we do better?

Java 7 added the [MethodHandle](https://docs.oracle.com/en/java/javase/16/docs/api/java.base/java/lang/invoke/MethodHandle.html) and
[CallSite](https://docs.oracle.com/en/java/javase/16/docs/api/java.base/java/lang/invoke/CallSite.html) classes, which are usually
used with the invokedynamic bytecode (used, for example, to implement lambdas), but it can be used directly by java code too.

In particular, for our event handling purposes, [MutableCallSite](https://docs.oracle.com/en/java/javase/16/docs/api/java.base/java/lang/invoke/MutableCallSite.html)
is interesting, because it can be re-linked. Combined with a `static final` field, which is treated as a compile-time constant by the JIT
compiler, it can be used to create quite interesting and useful classes, such as [constants but not really](https://github.com/forax/exotic/blob/ae4be6aa8e668a473d7771099fe1fa2ed56c0cc1/src/main/java/com.github.forax.exotic/com/github/forax/exotic/MostlyConstant.java),
which get optimized just like normal constants but can be re-linked to trigger recompilation with the new value.

Well, let's try it then:

```java
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;

public class Demo3 {
    private static final MutableCallSite CALL_SITE = new MutableCallSite(
            MethodHandles.empty(MethodType.methodType(void.class, AbsolutelyNothingHappenedEvent.class))
    );
    private static final MethodHandle DISPATCH_EVENT = CALL_SITE.dynamicInvoker();
    
    public static void main(String[] args) {
        for(int i = 0; i < 1_000_000; i++) {
            doAbsolutelyNothingButMaybeNotifyAbsolutelyNoOneFaster();
        }
    }
    
    private static class AbsolutelyNothingHappenedEvent {}
    
    private static void doAbsolutelyNothingButMaybeNotifyAbsolutelyNoOneFaster() {
        try { DISPATCH_EVENT.invokeExact(new AbsolutelyNothingHappenedEvent()); } catch(Throwable impossible) {}
    }
}
```

What's this?

```
[Verified Entry Point]
  # {method} {0x00007feff8c00518} 'doAbsolutelyNothingButMaybeNotifyAbsolutelyNoOneFaster' '()V' in 'Demo3'
  #           [sp+0x20]  (sp of caller)
  0x00007ff01019f680:   sub    rsp,0x18
  0x00007ff01019f687:   mov    QWORD PTR [rsp+0x10],rbp     ;*synchronization entry
                                                            ; - Demo3::doAbsolutelyNothingButMaybeNotifyAbsolutelyNoOneFaster@-1 (line 21)
  0x00007ff01019f68c:   add    rsp,0x10
  0x00007ff01019f690:   pop    rbp
  0x00007ff01019f691:   cmp    rsp,QWORD PTR [r15+0x118]    ;   {poll_return}
  0x00007ff01019f698:   ja     0x00007ff01019f69f
  0x00007ff01019f69e:   ret
  0x00007ff01019f69f:   movabs r10,0x7ff01019f691           ;   {internal_word}
  0x00007ff01019f6a9:   mov    QWORD PTR [r15+0x3b0],r10
  0x00007ff01019f6b0:   jmp    0x00007ff00871d000           ;   {runtime_call SafepointBlob}
```

We're back to the original code!

Does it perform similarly as well? (it does, the code is exactly the same, here's the benchmark anyway)

```
Benchmark                                                                      Mode  Cnt           Score           Error  Units
CanItReallyBeThatSlow.doAbsolutelyNothing                                     thrpt    6  3785088934.978 ± 112415907.564  ops/s
CanItReallyBeThatSlow.doAbsolutelyNothingButMaybeNotifyAbsolutelyNoOneFaster  thrpt    6  3837570458.679 ± 168980563.913  ops/s   
```

## Notifying listeners

Well, now we can just add both approaches and get a fast dispatcher, right?

```java
public class Demo4 {
    private static final MutableCallSite CALL_SITE = new MutableCallSite(MethodType.methodType(void.class, AbsolutelyNothingHappenedEvent.class));
    private static final MethodHandle DISPATCH_EVENT = CALL_SITE.dynamicInvoker();
    
    public static void main(String[] args) throws Throwable {
        var dispatcher = new EventDispatcher<AbsolutelyNothingHappenedEvent>();
        CALL_SITE.setTarget(MethodHandles.lookup()
                                    .bind(dispatcher, "handle", MethodType.methodType(void.class, Object.class))
                                    .asType(CALL_SITE.type())
        );
        for(int i = 0; i < 1_000_000; i++) {
            doAbsolutelyNothingButSurelyItsFastRight();
        }
    }
    
    private static void doAbsolutelyNothingButSurelyItsFastRight() throws Throwable {
        DISPATCH_EVENT.invokeExact(new AbsolutelyNothingHappenedEvent());
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
}
```

```
[Verified Entry Point]
  # {method} {0x00007f4a61c00640} 'doAbsolutelyNothingButSurelyItsFastRight' '()V' in 'Demo4'
  #           [sp+0x30]  (sp of caller)
  0x00007f4a841a0720:   mov    DWORD PTR [rsp-0x14000],eax
  0x00007f4a841a0727:   push   rbp
  0x00007f4a841a0728:   sub    rsp,0x20                     ;*synchronization entry
                                                            ; - Demo4::doAbsolutelyNothingButSurelyItsFastRight@-1 (line 25)
  0x00007f4a841a072c:   movabs r10,0x69f933ec0              ;   {oop(a 'Demo4$EventDispatcher'{0x000000069f933ec0})}
  0x00007f4a841a0736:   mov    r11d,DWORD PTR [r10+0xc]     ;*getfield list {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - Demo4$EventDispatcher::handle@1 (line 34)
                                                            ; - java.lang.invoke.LambdaForm$DMH/0x0000000800c01800::invokeSpecial@11
                                                            ; - java.lang.invoke.LambdaForm$MH/0x0000000800c03000::invoke@28
                                                            ; - java.lang.invoke.LambdaForm$MH/0x0000000800c02c00::invoke@51
                                                            ; - java.lang.invoke.LambdaForm$MH/0x0000000800c02800::invokeExact_MT@19
                                                            ; - Demo4::doAbsolutelyNothingButSurelyItsFastRight@10 (line 25)
  0x00007f4a841a073a:   mov    r10d,DWORD PTR [r12+r11*8+0x8]; implicit exception: dispatches to 0x00007f4a841a0790
  0x00007f4a841a073f:   cmp    r10d,0x2031                  ;   {metadata('java/util/ArrayList')}
  0x00007f4a841a0746:   jne    0x00007f4a841a0768
  0x00007f4a841a0748:   lea    r10,[r12+r11*8]              ;*invokeinterface iterator {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - Demo4$EventDispatcher::handle@4 (line 34)
                                                            ; - java.lang.invoke.LambdaForm$DMH/0x0000000800c01800::invokeSpecial@11
                                                            ; - java.lang.invoke.LambdaForm$MH/0x0000000800c03000::invoke@28
                                                            ; - java.lang.invoke.LambdaForm$MH/0x0000000800c02c00::invoke@51
                                                            ; - java.lang.invoke.LambdaForm$MH/0x0000000800c02800::invokeExact_MT@19
                                                            ; - Demo4::doAbsolutelyNothingButSurelyItsFastRight@10 (line 25)
  0x00007f4a841a074c:   mov    r11d,DWORD PTR [r10+0x10]    ;*getfield size {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - java.util.ArrayList$Itr::hasNext@8 (line 962)
                                                            ; - Demo4$EventDispatcher::handle@11 (line 34)
                                                            ; - java.lang.invoke.LambdaForm$DMH/0x0000000800c01800::invokeSpecial@11
                                                            ; - java.lang.invoke.LambdaForm$MH/0x0000000800c03000::invoke@28
                                                            ; - java.lang.invoke.LambdaForm$MH/0x0000000800c02c00::invoke@51
                                                            ; - java.lang.invoke.LambdaForm$MH/0x0000000800c02800::invokeExact_MT@19
                                                            ; - Demo4::doAbsolutelyNothingButSurelyItsFastRight@10 (line 25)
  0x00007f4a841a0750:   test   r11d,r11d
  0x00007f4a841a0753:   jne    0x00007f4a841a0778           ;*if_icmpeq {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - java.util.ArrayList$Itr::hasNext@11 (line 962)
                                                            ; - Demo4$EventDispatcher::handle@11 (line 34)
                                                            ; - java.lang.invoke.LambdaForm$DMH/0x0000000800c01800::invokeSpecial@11
                                                            ; - java.lang.invoke.LambdaForm$MH/0x0000000800c03000::invoke@28
                                                            ; - java.lang.invoke.LambdaForm$MH/0x0000000800c02c00::invoke@51
                                                            ; - java.lang.invoke.LambdaForm$MH/0x0000000800c02800::invokeExact_MT@19
                                                            ; - Demo4::doAbsolutelyNothingButSurelyItsFastRight@10 (line 25)
  0x00007f4a841a0755:   add    rsp,0x20
  0x00007f4a841a0759:   pop    rbp
  0x00007f4a841a075a:   cmp    rsp,QWORD PTR [r15+0x118]    ;   {poll_return}
  0x00007f4a841a0761:   ja     0x00007f4a841a079c
  0x00007f4a841a0767:   ret
```

What the hell?! We're back to `Demo2`! Why? Isn't MethodHandle supposed to be fast?

Well, not so fast. We just indirectly did the same thing as Demo2, using MethodHandle/CallSite as basically a reflective call (although faster).

Let's look at the numbers

```
Benchmark                                                                 Mode  Cnt           Score          Error  Units
CanItReallyBeThatSlow.doAbsolutelyNothingAndNotifyButSurelyItsFastRight  thrpt    6   417958567.941 ±  4104865.027  ops/s
CanItReallyBeThatSlow.doAbsolutelyNothingButSurelyItsFastRight           thrpt    6  1821810563.304 ± 41438685.155  ops/s 
```

Yup, same speed (and assembly dumps match).

## What now?

Well, there's one trick left in the MethodHandle camp: [MethodHandle combinators](https://docs.oracle.com/en/java/javase/16/docs/api/java.base/java/lang/invoke/MethodHandles.html).

What if, instead of iterating a list of consumers, which could be anything and might do absolutely nothing themselves, we
build a single MethodHandle that calls all handlers, without going through the list?

Since all handlers have a `void` return, `MethodHandles#foldArguments` does exactly what we need:

```java
public class Demo5 {
    private static final MHEventDispatcher DISPATCHER = new MHEventDispatcher(AbsolutelyNothingHappenedEvent.class);
    private static final MethodHandle DISPATCH_EVENT = DISPATCHER.callSite.dynamicInvoker();
    
    public static void main(String[] args) throws Throwable {
        DISPATCHER.add(
                MethodHandles.lookup()
                        .findStatic(Demo5.class, "handleEvent", MethodType.methodType(void.class, AbsolutelyNothingHappenedEvent.class))
                        .asType(MethodType.methodType(void.class, Object.class))
        );
        for(int i = 0; i < 1_000_000; i++) {
            doAbsolutelyNothingButWeFinallyMadeItFast();
        }
    }
    
    private static void handleEvent(AbsolutelyNothingHappenedEvent event) {}
    
    private static void doAbsolutelyNothingButWeFinallyMadeItFast() throws Throwable {
        DISPATCH_EVENT.invokeExact(new AbsolutelyNothingHappenedEvent());
    }
    
    private static class AbsolutelyNothingHappenedEvent {}
    
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
```

```
[Verified Entry Point]
  # {method} {0x00007ff8bbc006d0} 'doAbsolutelyNothingButWeFinallyMadeItFast' '()V' in 'Demo5'
  #           [sp+0x20]  (sp of caller)
  0x00007ff8e81a1000:   sub    rsp,0x18
  0x00007ff8e81a1007:   mov    QWORD PTR [rsp+0x10],rbp     ;*synchronization entry
                                                            ; - Demo5::doAbsolutelyNothingButWeFinallyMadeItFast@-1 (line 26)
  0x00007ff8e81a100c:   add    rsp,0x10
  0x00007ff8e81a1010:   pop    rbp
  0x00007ff8e81a1011:   cmp    rsp,QWORD PTR [r15+0x118]    ;   {poll_return}
  0x00007ff8e81a1018:   ja     0x00007ff8e81a101f
  0x00007ff8e81a101e:   ret
  0x00007ff8e81a101f:   movabs r10,0x7ff8e81a1011           ;   {internal_word}
  0x00007ff8e81a1029:   mov    QWORD PTR [r15+0x3b0],r10
  0x00007ff8e81a1030:   jmp    0x00007ff8e071d000           ;   {runtime_call SafepointBlob}
```

Success! Now just in case, here's our benchmark

```
Benchmark                                                                      Mode  Cnt           Score           Error  Units
CanItReallyBeThatSlow.doAbsolutelyNothingButWeFinallyMadeItFast               thrpt    6  3792620234.223 ± 213923275.943  ops/s
CanItReallyBeThatSlow.doAbsolutelyNothing                                     thrpt    6  3785088934.978 ± 112415907.564  ops/s
CanItReallyBeThatSlow.doAbsolutelyNothingButMaybeNotifyAbsolutelyNoOneFaster  thrpt    6  3837570458.679 ± 168980563.913  ops/s  
```

Here's a new JMH run with all tests (and [here's](https://github.com/natanbc/natanbc.github.io/blob/master/code/2021-04-10-CanItReallyBeThatSlow.java) the source, if you want to run it yourself)

```
Benchmark                                                                          Mode  Cnt           Score           Error  Units
CanItReallyBeThatSlow.doAbsolutelyNothing                                         thrpt    6  3813455676.328 ± 208227149.803  ops/s
CanItReallyBeThatSlow.stillDoAbsolutelyNothingButMaybeNotifyAbsolutelyNoOne       thrpt    6  1824107944.290 ±  38913873.410  ops/s
CanItReallyBeThatSlow.doAbsolutelyNothingAgainButNowNotifySomeoneThatDoesNothing  thrpt    6   413168292.329 ±   4472153.767  ops/s
CanItReallyBeThatSlow.doAbsolutelyNothingButMaybeNotifyAbsolutelyNoOneFaster      thrpt    6  3756409162.758 ± 160163241.990  ops/s
CanItReallyBeThatSlow.doAbsolutelyNothingButSurelyItsFastRight                    thrpt    6  1817058873.222 ±  18819179.987  ops/s
CanItReallyBeThatSlow.doAbsolutelyNothingAndNotifyButSurelyItsFastRight           thrpt    6   416675808.683 ±   5225453.517  ops/s
CanItReallyBeThatSlow.doAbsolutelyNothingButWeFinallyMadeItFast                   thrpt    6  3761950075.005 ± 160857462.165  ops/s  
```

## Downsides

While this is definitely fast, it's not always better: adding and removing listeners needs the JIT to recompile the methods that
fire those events, which can be an issue if you need to frequently add/remove them. A mitigation is to add a boolean flag,
indicating whether or not the listener should ignore the event, and flip that instead of removing/adding it again.

Another issue is that you need `static final` fields or some other way of having [JIT constants](https://shipilev.net/jvm/anatomy-quarks/15-just-in-time-constants/)
(such as [records](https://openjdk.java.net/jeps/395), whose fields are [*trusted finals*](https://shipilev.net/jvm/anatomy-quarks/17-trust-nonstatic-final-fields/),
see [this](https://github.com/openjdk/jdk/blob/627ad9fe22a153410c14d0b2061bb7dee2c300af/src/hotspot/share/ci/ciField.cpp#L240))

For my purposes, I'm fine with those downsides, given the performance win it gets me.

## Bonus

What if you're calling the listeners because they may want to change some value? Well, [let's try that](https://github.com/natanbc/natanbc.github.io/blob/master/code/2021-04-10-Bonus.java)

```
Benchmark                       Mode  Cnt          Score          Error  Units
Bonus.baseline                 thrpt    6  653725028.558 ±  7222646.120  ops/s
Bonus.directCalls              thrpt    6  652196710.172 ±  6306907.872  ops/s
Bonus.methodHandleExact        thrpt    6  651047195.449 ± 13019249.297  ops/s
Bonus.methodHandleExactAsType  thrpt    6  653179014.931 ±  8035176.758  ops/s
Bonus.methodHandleGeneric      thrpt    6  255198958.913 ±  1963855.643  ops/s
Bonus.regularDispatcher        thrpt    6  303807064.139 ±  3364663.588  ops/s 
```

But we're still doing an allocation in possibly hot code, aren't we? Luckily, JMH has a profiler to test just that: `-prof gc`

```
Benchmark                                                    Mode  Cnt          Score         Error   Units
Bonus.baseline                                              thrpt    6  656961110.320 ± 5506647.010   ops/s
Bonus.baseline:·gc.alloc.rate                               thrpt    6         ≈ 10⁻⁴                MB/sec
Bonus.baseline:·gc.alloc.rate.norm                          thrpt    6         ≈ 10⁻⁷                  B/op
Bonus.baseline:·gc.count                                    thrpt    6            ≈ 0                counts
Bonus.directCalls                                           thrpt    6  654566313.567 ± 7167583.236   ops/s
Bonus.directCalls:·gc.alloc.rate                            thrpt    6         ≈ 10⁻⁴                MB/sec
Bonus.directCalls:·gc.alloc.rate.norm                       thrpt    6         ≈ 10⁻⁷                  B/op
Bonus.directCalls:·gc.count                                 thrpt    6            ≈ 0                counts
Bonus.methodHandleExact                                     thrpt    6  652152784.316 ± 7774435.959   ops/s
Bonus.methodHandleExact:·gc.alloc.rate                      thrpt    6         ≈ 10⁻⁴                MB/sec
Bonus.methodHandleExact:·gc.alloc.rate.norm                 thrpt    6         ≈ 10⁻⁷                  B/op
Bonus.methodHandleExact:·gc.count                           thrpt    6            ≈ 0                counts
Bonus.methodHandleExactAsType                               thrpt    6  652964367.420 ± 9377027.811   ops/s
Bonus.methodHandleExactAsType:·gc.alloc.rate                thrpt    6         ≈ 10⁻⁴                MB/sec
Bonus.methodHandleExactAsType:·gc.alloc.rate.norm           thrpt    6         ≈ 10⁻⁷                  B/op
Bonus.methodHandleExactAsType:·gc.count                     thrpt    6            ≈ 0                counts
Bonus.methodHandleGeneric                                   thrpt    6  253694670.403 ± 3020649.871   ops/s
Bonus.methodHandleGeneric:·gc.alloc.rate                    thrpt    6       3317.571 ±      39.227  MB/sec
Bonus.methodHandleGeneric:·gc.alloc.rate.norm               thrpt    6         16.001 ±       0.001    B/op
Bonus.methodHandleGeneric:·gc.churn.G1_Eden_Space           thrpt    6       3328.680 ±     154.792  MB/sec
Bonus.methodHandleGeneric:·gc.churn.G1_Eden_Space.norm      thrpt    6         16.055 ±       0.737    B/op
Bonus.methodHandleGeneric:·gc.churn.G1_Survivor_Space       thrpt    6          0.007 ±       0.007  MB/sec
Bonus.methodHandleGeneric:·gc.churn.G1_Survivor_Space.norm  thrpt    6         ≈ 10⁻⁴                  B/op
Bonus.methodHandleGeneric:·gc.count                         thrpt    6        187.000                counts
Bonus.methodHandleGeneric:·gc.time                          thrpt    6        141.000                    ms
Bonus.regularDispatcher                                     thrpt    6  304669694.303 ± 3193656.861   ops/s
Bonus.regularDispatcher:·gc.alloc.rate                      thrpt    6         ≈ 10⁻⁴                MB/sec
Bonus.regularDispatcher:·gc.alloc.rate.norm                 thrpt    6         ≈ 10⁻⁶                  B/op
Bonus.regularDispatcher:·gc.count                           thrpt    6            ≈ 0                counts
```

Surprisingly (or not, if you know the [relevant optimizations](https://shipilev.net/jvm/anatomy-quarks/18-scalar-replacement)),
there's basically no allocation going on in most tests, except in methodHandleGeneric. This difference can be explained by
reading the javadoc for that method:


> If the call site's symbolic type descriptor exactly matches this method handle's type, the call proceeds as if by invokeExact.
>
> Otherwise, the call proceeds as if this method handle were first adjusted by calling asType to adjust this method handle to the required type, and then the call proceeds as if by invokeExact on the adjusted method handle.
>
> There is no guarantee that the asType call is actually made. If the JVM can predict the results of making the call, it may perform adaptations directly on the caller's arguments, and call the target method handle according to its own exact type.

Looks like my JVM is not smart enough to predict the result, so it kept the allocations around (some digging into the generated machine code
would tell if at least the EventWithVal allocation was removed or not, but this post is long enough as-is).
