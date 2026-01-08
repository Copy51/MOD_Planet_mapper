package com.example.planetmapper.space;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Represents a celestial body in space (planet, star, moon, asteroid).
 * Supports N-Body physics simulation.
 */
public class CelestialBody {

    public enum BodyType {
        STAR,
        PLANET,
        MOON,
        ASTEROID
    }

    // Core Identity
    private final UUID id;
    private String name;

    // Physics State
    private Vec3 position;
    private Vec3 velocity;
    private double mass;
    private float radius;

    // Visual / Type
    private ResourceLocation texture;
    private BodyType type;

    // Rotation
    private float rotationSpeed; // degrees per tick
    private float currentRotation; // current rotation angle
    private float axialTilt; // degrees

    // Visual Color
    private float colorR = 1.0f;
    private float colorG = 1.0f;
    private float colorB = 1.0f;
    private double temperatureK = -1.0; // Used mainly for stars to derive color
    private ResourceLocation targetDimension; // The dimension this planet links to (for Immersive Portals)

    // Constructor
    public CelestialBody(UUID id, String name, Vec3 position, float radius, double mass, BodyType type) {
        this.id = id;
        this.name = name;
        this.position = position;
        this.velocity = Vec3.ZERO;
        this.mass = mass;
        this.radius = radius;
        this.type = type;
        this.texture = null;
        this.rotationSpeed = 0.0f;
        this.currentRotation = 0.0f;
        this.axialTilt = 0.0f;
    }

    // Factory methods
    public static CelestialBody createStar(String name, Vec3 position, float radius) {
        // Star Mass ~ 100,000 internal units
        CelestialBody body = new CelestialBody(UUID.randomUUID(), name, position, radius, 100000.0, BodyType.STAR);
        body.setColor(1.0f, 1.0f, 0.8f);
        return body;
    }

    public static CelestialBody createPlanet(String name, Vec3 position, float radius) {
        // Planet Mass ~ 100 units
        CelestialBody body = new CelestialBody(UUID.randomUUID(), name, position, radius, 100.0, BodyType.PLANET);
        return body;
    }

    // Visual Tick (Physics tick happens in SpacePhysicsHandler)
    public void tick() {
        currentRotation += rotationSpeed;
        if (currentRotation >= 360.0f)
            currentRotation -= 360.0f;
    }

    public Vec3 getWorldPosition(CelestialBodyRegistry registry) {
        return position;
    }

    // NBT Serialization
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putString("name", name);
        tag.putDouble("posX", position.x);
        tag.putDouble("posY", position.y);
        tag.putDouble("posZ", position.z);
        tag.putDouble("velX", velocity.x);
        tag.putDouble("velY", velocity.y);
        tag.putDouble("velZ", velocity.z);
        tag.putDouble("mass", mass);
        tag.putFloat("radius", radius);
        tag.putString("type", type.name());
        tag.putFloat("rotationSpeed", rotationSpeed);
        tag.putFloat("currentRotation", currentRotation);
        tag.putFloat("axialTilt", axialTilt);
        tag.putFloat("colorR", colorR);
        tag.putFloat("colorG", colorG);
        tag.putFloat("colorB", colorB);
        tag.putDouble("temperatureK", temperatureK);

        if (texture != null) {
            tag.putString("texture", texture.toString());
        }
        if (targetDimension != null) {
            tag.putString("targetDimension", targetDimension.toString());
        }

        return tag;
    }

    public static CelestialBody load(CompoundTag tag) {
        UUID id = tag.getUUID("id");
        String name = tag.getString("name");
        Vec3 pos = new Vec3(tag.getDouble("posX"), tag.getDouble("posY"), tag.getDouble("posZ"));
        float radius = tag.getFloat("radius");

        // Handle legacy data (if any existed before adding mass)
        double mass = tag.contains("mass") ? tag.getDouble("mass") : (radius * 1.0);
        BodyType type = BodyType.valueOf(tag.getString("type"));

        CelestialBody body = new CelestialBody(id, name, pos, radius, mass, type);

        if (tag.contains("velX")) {
            body.velocity = new Vec3(tag.getDouble("velX"), tag.getDouble("velY"), tag.getDouble("velZ"));
        }

        body.rotationSpeed = tag.getFloat("rotationSpeed");
        body.currentRotation = tag.getFloat("currentRotation");
        body.axialTilt = tag.getFloat("axialTilt");
        body.colorR = tag.getFloat("colorR");
        body.colorG = tag.getFloat("colorG");
        body.colorB = tag.getFloat("colorB");
        body.temperatureK = tag.contains("temperatureK") ? tag.getDouble("temperatureK") : -1.0;

        if (tag.contains("texture")) {
            body.texture = ResourceLocation.parse(tag.getString("texture"));
        }
        if (tag.contains("targetDimension")) {
            body.targetDimension = ResourceLocation.parse(tag.getString("targetDimension"));
        }

        return body;
    }

    // Getters and Setters
    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Vec3 getPosition() {
        return position;
    }

    public void setPosition(Vec3 position) {
        this.position = position;
    }

    public Vec3 getVelocity() {
        return velocity;
    }

    public void setVelocity(Vec3 velocity) {
        this.velocity = velocity;
    }

    public double getMass() {
        return mass;
    }

    public void setMass(double mass) {
        this.mass = mass;
    }

    public float getRadius() {
        return radius;
    }

    public void setRadius(float radius) {
        this.radius = radius;
    }

    public ResourceLocation getTexture() {
        return texture;
    }

    public void setTexture(ResourceLocation texture) {
        this.texture = texture;
    }

    public BodyType getType() {
        return type;
    }

    public void setType(BodyType type) {
        this.type = type;
    }

    public float getRotationSpeed() {
        return rotationSpeed;
    }

    public void setRotationSpeed(float rotationSpeed) {
        this.rotationSpeed = rotationSpeed;
    }

    public float getCurrentRotation() {
        return currentRotation;
    }

    public float getAxialTilt() {
        return axialTilt;
    }

    public void setAxialTilt(float axialTilt) {
        this.axialTilt = axialTilt;
    }

    public float getColorR() {
        return colorR;
    }

    public float getColorG() {
        return colorG;
    }

    public float getColorB() {
        return colorB;
    }

    public void setColor(float r, float g, float b) {
        this.colorR = r;
        this.colorG = g;
        this.colorB = b;
    }

    public double getTemperatureK() {
        return temperatureK;
    }

    public void setTemperatureK(double temperatureK) {
        this.temperatureK = temperatureK;
    }

    public ResourceLocation getTargetDimension() {
        return targetDimension;
    }

    public void setTargetDimension(ResourceLocation targetDimension) {
        this.targetDimension = targetDimension;
    }

    /**
     * Approximate black-body color for given temperature in Kelvin.
     * Returns normalized RGB (0..1).
     */
    public static float[] temperatureToRGB(double temperatureK) {
        double t = Math.max(1000.0, Math.min(40000.0, temperatureK));
        t /= 100.0;

        double r;
        double g;
        double b;

        if (t <= 66.0) {
            r = 255.0;
            g = 99.4708025861 * Math.log(t) - 161.1195681661;
            if (t <= 19.0) {
                b = 0.0;
            } else {
                b = 138.5177312231 * Math.log(t - 10.0) - 305.0447927307;
            }
        } else {
            r = 329.698727446 * Math.pow(t - 60.0, -0.1332047592);
            g = 288.1221695283 * Math.pow(t - 60.0, -0.0755148492);
            b = 255.0;
        }

        r = clampColor(r);
        g = clampColor(g);
        b = clampColor(b);

        return new float[] { (float) (r / 255.0), (float) (g / 255.0), (float) (b / 255.0) };
    }

    private static double clampColor(double val) {
        if (val < 0.0)
            return 0.0;
        if (val > 255.0)
            return 255.0;
        return val;
    }
}
