package com.cna;

import com.cna.AgentInput.DefaultAgentInputUnit;
import com.cna.config.ConfigsManager;
import lombok.extern.slf4j.Slf4j;

import java.net.URISyntaxException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Slf4j
public class Main {

    public static BlockingQueue<DefaultAgentInputUnit> AgentInputTasksQueue = new LinkedBlockingQueue<>(4096);

    public static NapcatAdapter GlobalNapcatAdapter;
    public static LivingLoop loop = new LivingLoop();

    public static void main(String[] args){

        ConfigsManager.init();

        try {
            GlobalNapcatAdapter = new NapcatAdapter();
            GlobalNapcatAdapter.connect();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }

        loop.start();

    }
}
