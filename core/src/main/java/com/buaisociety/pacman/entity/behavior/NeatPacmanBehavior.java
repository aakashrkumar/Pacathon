package com.buaisociety.pacman.entity.behavior;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.buaisociety.pacman.maze.Maze;
import com.buaisociety.pacman.maze.Tile;
import com.buaisociety.pacman.maze.TileState;
import com.buaisociety.pacman.sprite.DebugDrawing;
import com.cjcrafter.neat.Client;
import com.buaisociety.pacman.entity.Direction;
import com.buaisociety.pacman.entity.Entity;
import com.buaisociety.pacman.entity.PacmanEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2d;
import org.joml.Vector2i;
import org.joml.Vector2ic;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Queue;

public class NeatPacmanBehavior implements Behavior {

    private final @NotNull Client client;
    private @Nullable PacmanEntity pacman;

    private Direction forward = Direction.UP;
    private Direction left = Direction.LEFT;
    private Direction right = Direction.RIGHT;
    private Direction behind = Direction.DOWN;

    // Score modifiers help us maintain "multiple pools" of points.
    // This is great for training, because we can take away points from
    // specific pools of points instead of subtracting from all.
    private int scoreModifier = 0;

    public ArrayList<Direction> moveHistory = new ArrayList<>();

    int lastScore = 0;
    int updateSinceLastScore = 0;

    int[][] distances;

    public NeatPacmanBehavior(@NotNull Client client) {
        this.client = client;
    }

    /**
     * Returns the desired direction that the entity should move towards.
     *
     * @param entity the entity to get the direction for
     * @return the desired direction for the entity
     */
    @NotNull
    @Override
    public Direction getDirection(@NotNull Entity entity) {
        if (pacman == null) {
            pacman = (PacmanEntity) entity;
        }
        distances = computeDistances();
        Vector2ic dimensions = pacman.getMaze().getDimensions();
        // We are going to use these directions a lot for different inputs. Get them all once for clarity and brevity
        forward = pacman.getDirection();
        left = pacman.getDirection().left();
        right = pacman.getDirection().right();
        behind = pacman.getDirection().behind();


        // SPECIAL TRAINING CONDITIONS
        // TODO: Make changes here to help with your training...
        int newScore = pacman.getMaze().getLevelManager().getScore();
        if (newScore > lastScore) {
            lastScore = newScore;
            updateSinceLastScore = 0;
        } else {
            updateSinceLastScore++;
        }

        if (updateSinceLastScore > 60 * 20) {
            pacman.kill();
            return Direction.UP;

        }

        // END OF SPECIAL TRAINING CONDITIONS


        // Input nodes 1, 2, 3, and 4 show if the pacman can move in the forward, left, right, and behind directions
        boolean canMoveForward = pacman.canMove(forward);
        boolean canMoveLeft = pacman.canMove(left);
        boolean canMoveRight = pacman.canMove(right);
        boolean canMoveBehind = pacman.canMove(behind);

        float[] rayCast = new float[4];
        for (int i = 0; i < 4; i++) {
            Direction direction = switch (i) {
                case 0 -> forward;
                case 1 -> left;
                case 2 -> right;
                case 3 -> behind;
                default -> throw new IllegalStateException("Unexpected value: " + i);
            };
            Vector2i position = new Vector2i(pacman.getTilePosition().x(), pacman.getTilePosition().y());
            while (pacman.getMaze().getTile(position).getState() != TileState.WALL) {
                position.add(direction.getDx(), direction.getDy());
                if (position.x() < 0 || position.x() >= pacman.getMaze().getDimensions().x() || position.y() < 0 || position.y() >= pacman.getMaze().getDimensions().y()) {
                    break;
                }
                rayCast[i] = (float) Math.sqrt(Math.pow((0.0 + position.x() - pacman.getTilePosition().x())/dimensions.x(), 2) + Math.pow((position.y() - pacman.getTilePosition().y() + 0.0)/dimensions.y(), 2));
            }
        }


        Tile nearestPellet = getNearestPellet();
        Vector2d relativeCoordinates = translateRelative(nearestPellet.getPosition());

        float[] outputs = client.getCalculator().calculate(new float[]{
            rayCast[0],
            rayCast[1],
            rayCast[2],
            rayCast[3],
            (float) relativeCoordinates.x() / (float) dimensions.x(),
            (float) relativeCoordinates.y() / (float) dimensions.y()
        }).join();

        int index = 0;
        float max = outputs[0];
        for (int i = 1; i < outputs.length; i++) {
            if (outputs[i] > max) {
                max = outputs[i];
                index = i;
            }
        }

        Direction newDirection = switch (index) {
            case 0 -> pacman.getDirection();
            case 1 -> pacman.getDirection().left();
            case 2 -> pacman.getDirection().right();
            case 3 -> pacman.getDirection().behind();
            default -> throw new IllegalStateException("Unexpected value: " + index);
        };

        client.setScore(pacman.getMaze().getLevelManager().getScore() + scoreModifier); // need to improve score hurisitcs
        // ideas
        /*
         * Give reward for moving in direction of nearest pellet
         * Give reward for eating pellet
         * Give reward for eating power pellet
         * Give reward for eating fruit
         * Give reward for eating ghost
         * Give penalty for getting eaten by ghost
         * Being alive is a penalty unless you run out of power pellets
         *
         * Reward engineering
         */
        moveHistory.add(newDirection);
        return newDirection;
    }

    @Override
    public void render(@NotNull SpriteBatch batch) {
        // TODO: You can render debug information here
        /*
        if (pacman != null) {
            DebugDrawing.outlineTile(batch, pacman.getMaze().getTile(pacman.getTilePosition()), Color.RED);
            DebugDrawing.drawDirection(batch, pacman.getTilePosition().x() * Maze.TILE_SIZE, pacman.getTilePosition().y() * Maze.TILE_SIZE, pacman.getDirection(), Color.RED);
        }
         */
        if(pacman != null){
            Tile nearestPellet = getNearestPellet();
            DebugDrawing.outlineTile(batch, nearestPellet, Color.GREEN);
        }
    }

    public Vector2d translateRelative(Vector2ic coordinates) {
        // new coordinates are relative to pacman

        Vector2i pacmanCoordinates = pacman.getTilePosition();
        Vector2d relative = new Vector2d(coordinates.x() - pacmanCoordinates.x(), coordinates.y() - pacmanCoordinates.y());
        // also orient the coordinates to the direction of pacman
        /*
        * ex
        * pelletX = (float) (relative.x() * forward.getDx() + relative.x() * right.getDx());
        * pelletY = (float) (relative.y() * forward.getDy() + relative.y() * right.getDy());*/
        double newCoordinatesX = relative.x() * forward.getDx() + relative.x() * right.getDx();
        double newCoordinatesY = relative.y() * forward.getDy() + relative.y() * right.getDy();
        relative.set(newCoordinatesX, newCoordinatesY);
        return relative;
    }

    public Tile getNearestPellet() {
        Vector2ic dimensions = pacman.getMaze().getDimensions();
        Tile nearestPellet = null;
        for (int y = 0; y < dimensions.y(); y++) {
            for (int x = 0; x < dimensions.x(); x++) {
                Tile tile = pacman.getMaze().getTile(x, y);
                if (tile.getState() == TileState.PELLET || tile.getState() == TileState.POWER_PELLET) {
                    if (nearestPellet == null) {
                        nearestPellet = tile;
                    } else {
                        if (distances[x][y] < distances[nearestPellet.getPosition().x()][nearestPellet.getPosition().y()]) {
                            nearestPellet = tile;
                        }
                    }
                }
            }
        }
        return nearestPellet;
    }

    public int[][] computeDistances() {
        // path search
        Vector2ic dimensions = this.pacman.getMaze().getDimensions();
        Vector2ic pacmanPosition = this.pacman.getTilePosition();
        int[][] distance = new int[dimensions.x()][dimensions.y()];
        for (int y = 0; y < dimensions.y(); y++) {
            for (int x = 0; x < dimensions.x(); x++) {
                distance[x][y] = -1;
            }
        }
        distance[pacmanPosition.x()][pacmanPosition.y()] = 0;
        Queue<Vector2ic> queue = new LinkedList<>();
        queue.add(pacmanPosition);
        while (!queue.isEmpty()) {
            Vector2ic current = queue.poll();
            for (Direction direction : Direction.values()) {
                int dx = direction.getDx();
                int dy = direction.getDy();
                Vector2i next = new Vector2i(current.x() + dx, current.y() + dy);
                if (next.x() < 0 || next.x() >= dimensions.x() || next.y() < 0 || next.y() >= dimensions.y()) {
                    continue;
                }
                if (this.pacman.getMaze().getTile(next).getState() != TileState.WALL && distance[next.x()][next.y()] == -1) {
                    distance[next.x()][next.y()] = distance[current.x()][current.y()] + 1;
                    queue.add(next);
                }
            }
        }

        return distance;
    }

}
