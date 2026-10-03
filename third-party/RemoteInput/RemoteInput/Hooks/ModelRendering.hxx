//
//  ModelRendering.hpp
//  RemoteInput
//
//  Created by Brandon on 2019-12-08.
//  Copyright © 2019 XIO. All rights reserved.
//

#ifndef ModelRendering_hpp
#define ModelRendering_hpp

#if defined(DEBUG)
#include <cstdint>
#include <cmath>
#include <vector>
#include "JNI_Common.hxx"
#include "Reflection.hxx"

class Reflector;

Reflector* get_reflector(Reflection* reflection);

static const double UNIT = M_PI / 1024.0;
static int SINE[2048] = {0};
static int COSINE[2048] = {0};

struct Point
{
    std::int32_t x;
    std::int32_t y;
    std::int32_t z;

    Point() : x(0), y(0), z(0) {}
    Point(std::int32_t x, std::int32_t y, std::int32_t z = 0) : x(x), y(y), z(z) {}

    Point rotate(std::int32_t orientation);
};

struct Triangle
{
    Point a;
    Point b;
    Point c;

    Triangle() {}
    Triangle(Point a, Point b, Point c) : a(a), b(b), c(c) {}
};

struct Polygon
{
    std::int32_t npoints;
    std::vector<std::int32_t> xpoints;
    std::vector<std::int32_t> ypoints;

    Polygon() {}
    Polygon(std::vector<std::int32_t> xpoints, std::vector<std::int32_t> ypoints, std::int32_t npoints);
};

class Client
{
private:
    typedef std::unique_ptr<void, std::function<void(void* const)>> object;

public:
    static std::uint8_t getTileSettings(Reflector* eios, std::int32_t x, std::int32_t y, std::int32_t z);
    static std::int32_t getTileHeights(Reflector* eios, std::int32_t x, std::int32_t y, std::int32_t z);
    static std::int32_t plane(Reflector* eios);
    static std::int32_t viewPortWidth(Reflector* eios);
    static std::int32_t viewPortHeight(Reflector* eios);
    static std::int32_t viewPortScale(Reflector* eios);
};

class Camera
{
private:
    typedef std::unique_ptr<void, std::function<void(void* const)>> object;

public:
    static std::int32_t getX(Reflector* eios);
    static std::int32_t getY(Reflector* eios);
    static std::int32_t getZ(Reflector* eios);
    static std::int32_t pitch(Reflector* eios);
    static std::int32_t yaw(Reflector* eios);
};

class Projector
{
private:
    static std::int32_t getTileHeight(Reflector* eios, std::int32_t x, std::int32_t y, std::int32_t plane);

public:
    static Point toScreen(Reflector* eios, Point p, std::int32_t localX, std::int32_t localY, std::int32_t plane);

    static Point toScreen(Reflector* eios, Point p, std::int32_t localX, std::int32_t localY, std::int32_t plane,
                          std::int32_t cameraX, std::int32_t cameraY, std::int32_t cameraZ,
                          std::int32_t cameraPitch, std::int32_t cameraYaw,
                          std::int32_t clientPlane,
                          std::int32_t viewPortWidth, std::int32_t viewPortHeight,
                          std::int32_t viewPortScale);
};

class Model
{
private:
    typedef std::unique_ptr<void, std::function<void(void* const)>> object;
    object ref;
    Reflector* eios;

    std::vector<Point> vertices;
    std::vector<Triangle> triangles;

public:
    Model(Reflector* eios, object&& ref);
    Model(Model&& other);
    Model& operator = (Model&& other);

    object getRef() { return std::move(ref); }
    void scale(std::int32_t x, std::int32_t y, std::int32_t z);
    std::vector<Point> getVertices();
    std::vector<Triangle> getTriangles();
    std::vector<Triangle> getRotatedTriangles(std::int32_t orientation);
    operator bool() const;
};

class Node
{
private:
    typedef std::unique_ptr<void, std::function<void(void* const)>> object;
    object ref;
    Reflector* eios;

public:
    Node() {}
    Node(Reflector* eios, object&& ref);
    Node(Node&& other);

    Node& operator = (Node&& other);
    object getRef() { return std::move(ref); }
    void* release() { return ref.release(); }
    std::int64_t uid();
    Node next();

    operator bool() const;
};


class HashTable
{
private:
    typedef std::unique_ptr<void, std::function<void(void* const)>> object;
    object ref;
    Reflector* eios;

public:
    HashTable(Reflector* eios, object&& ref);

    object getRef() { return std::move(ref); }
    std::int32_t size();
    std::vector<Node> buckets();

    operator bool() const;
};

class Cache
{
private:
    typedef std::unique_ptr<void, std::function<void(void* const)>> object;
    object ref;
    Reflector* eios;

public:
    Cache(Reflector* eios, object&& ref);
    Cache(Cache&& other);

    Cache& operator = (Cache&& other);

    object getRef() { return std::move(ref); }
    HashTable hash_table();

    operator bool() const;
};

class PlayerDefinition
{
private:
    typedef std::unique_ptr<void, std::function<void(void* const)>> object;
    object ref;
    Reflector* eios;

public:
    PlayerDefinition(Reflector* eios, object&& ref);
    PlayerDefinition(PlayerDefinition&& other);

    PlayerDefinition& operator = (PlayerDefinition&& other);

    object getRef() { return std::move(ref); }
    std::int32_t id();
    std::int64_t model_id();
    std::int64_t animated_model_id();
    bool is_female();
    Cache model_cache();

    operator bool() const;
};

class Player
{
private:
    typedef std::unique_ptr<void, std::function<void(void* const)>> object;
    object ref;
    Reflector* eios;

public:
    Player(Reflector* eios, object&& ref);
    Player(Player&& other);
    Player& operator = (Player&& other);

    object getRef() { return std::move(ref); }
    static Player localPlayer(Reflector* eios);
    static std::vector<Player> getAllPlayers(Reflector* eios);

    std::string name();
    std::int32_t orientation();
    std::int32_t getX();
    std::int32_t getY();
    PlayerDefinition definition();
    Model model();

    operator bool() const;
};


void draw_polygon(std::uint8_t* dest, std::int32_t npoints, std::int32_t* xpoints, std::int32_t* ypoints, std::int32_t width, std::int32_t height);
void draw_player(std::function<void(Polygon* p, std::int32_t width, std::int32_t height)> renderer);
#endif

#endif /* ModelRendering_hpp */
