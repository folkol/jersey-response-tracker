package com.folkol.jersey;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtMethod;
import javassist.LoaderClassPath;

/**
 * This Java Agent attemps to track orphaned jersey responses.
 */
public class TrackingAgent implements ClassFileTransformer {
    public static ConcurrentHashMap<Object, Exception> orphans = new ConcurrentHashMap<>();

    public static void premain(String agentArgument,
                               Instrumentation instrumentation)
    {
        instrumentation.addTransformer(new TrackingAgent());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Set<String> uniqueCallSites = orphans.values().stream().map(e -> {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                e.printStackTrace(pw);
                return sw.toString();
            }).collect(Collectors.toSet());

            if (uniqueCallSites.isEmpty()) {
                System.err.printf("=== %s: No orphaned responses found ====%n", TrackingAgent.class.getSimpleName());
            } else {
                System.err.printf("=== %s Orphaned responses created here ===%n%n", TrackingAgent.class.getSimpleName());
                uniqueCallSites.forEach(System.err::println);
                System.err.println("=== DONE ===");
            }
        }));
    }

    public byte[] transform(ClassLoader loader,
                            String className,
                            Class classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer)
    {
        byte[] byteCode = classfileBuffer;

        if (className.equals("org/glassfish/jersey/client/InboundJaxrsResponse")) {
            try {
                ClassPool cp = ClassPool.getDefault();
                cp.insertClassPath(new LoaderClassPath(loader));
                CtClass cc = cp.get("org.glassfish.jersey.client.InboundJaxrsResponse");

                CtConstructor ctor = cc.getDeclaredConstructor(new CtClass[]{
                    cp.get("org.glassfish.jersey.client.ClientResponse"),
                    cp.get("org.glassfish.jersey.process.internal.RequestScope")
                });
                ctor.insertBeforeBody("{com.folkol.jersey.TrackingAgent.orphans.put(this, new Exception());}");

                CtMethod close = cc.getDeclaredMethod("close");
                close.insertAfter("{com.folkol.jersey.TrackingAgent.orphans.remove(this);}");

                CtMethod[] declaredMethods = cc.getDeclaredMethods();
                for (CtMethod declaredMethod : declaredMethods) {
                    if (declaredMethod.getName().equals("readEntity")) {
                        declaredMethod.insertAfter("{com.folkol.jersey.TrackingAgent.orphans.remove(this);}");
                    }
                }

                byteCode = cc.toBytecode();
                cc.detach();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }

        return byteCode;
    }
}
