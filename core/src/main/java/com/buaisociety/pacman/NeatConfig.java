package com.buaisociety.pacman;

public class NeatConfig {
    // NEAT parameters
    public static int populationSize = 300;
    public static float mutateWeightChance = 0.75f;
    public static float weightCoefficient = 0.3f;
    public static int targetClientsPerSpecies = 12;
    public static int stagnationLimit = 10;

    public static boolean biasEnabled = true;

    public static int neatInputNodes = 8;
//    public static int neatInputNodes = 22;
    public static int neatOutputNodes = 6;


    public static boolean loadFromFile = false;
    public static String folder = "oct26-80";
    public static String file = "generation-70.json";

    public static boolean USE_TOURNAMENT_SETTINGS = false;
}
