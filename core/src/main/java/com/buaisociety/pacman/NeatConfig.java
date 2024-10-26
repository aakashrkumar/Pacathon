package com.buaisociety.pacman;

public class NeatConfig {
    // NEAT parameters
    public static float mutateWeightChance = 0.75f;
    public static float weightCoefficient = 1.0f;
    public static int targetClientsPerSpecies = 12;
    public static int stagnationLimit = 10; // what does this do?

    public static boolean biasEnabled = true;

    public static int neatInputNodes = 6;
    public static int neatOutputNodes = 4;
}
