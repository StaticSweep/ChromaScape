//
//  Random.hxx
//  RemoteInput
//
//  Created by Brandon on 2019-12-29.
//  Copyright © 2019 XIO. All rights reserved.
//

#ifndef RANDOM_HXX_INCLUDED
#define RANDOM_HXX_INCLUDED

#include <random>
#include <limits>
#include <memory>

class Random
{
private:
    Random() {}

    #if defined(__x86_64__)
    std::mt19937_64 rng{std::random_device()()};
    #else
    std::mt19937 rng{std::random_device()()};
    #endif

public:
    Random(Random const&) = delete;
    Random& operator = (Random const&) = delete;

    static std::shared_ptr<Random> instance() noexcept;

    std::int64_t generate_random_int(std::int64_t min, std::int64_t max) noexcept;
    float generate_random_float(float min, float max) noexcept;
    double generate_random_double(double min, double max) noexcept;
};

#endif /* RANDOM_HXX_INCLUDED */
