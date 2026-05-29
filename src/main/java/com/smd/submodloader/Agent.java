package com.smd.submodloader;

import java.lang.instrument.Instrumentation;

public class Agent {
    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("[SubModLoader] Agent starting...");

        // 1. 加载配置
        Helper.loadConfig();

        // 2. 注册 ClassFileTransformer
        inst.addTransformer(new Transformer(), true);
        System.out.println("[SubModLoader] Transformer registered.");
    }
}
