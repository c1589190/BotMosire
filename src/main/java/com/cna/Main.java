package com.cna;

import com.cna.AgentInput.DefaultAgentInputUnit;
import com.cna.config.ConfigsManager;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Slf4j
public class Main {

    public static BlockingQueue<DefaultAgentInputUnit> AgentInputTasksQueue = new LinkedBlockingQueue<>(4096);

    public static NapcatAdapter GlobalNapcatAdapter = new NapcatAdapter(3001, "http://127.0.0.1:3000");
    public static LivingLoop loop = new LivingLoop();

    public static void main(String[] args){

        ConfigsManager.init();

        GlobalNapcatAdapter.start();

        loop.start();

    }
}
