static inline int clamp_coord(int c, int maxInclusive) {
    if (c < 0) {
        return 0;
    }
    return c > maxInclusive ? maxInclusive : c;
}

static inline int clamp255(int v) {
    if (v < 0) {
        return 0;
    }
    return v > 255 ? 255 : v;
}

static inline int isqrt_int(int n) {
    if (n <= 0) {
        return 0;
    }
    int lo = 1;
    int hi = n < 46340 ? n : 46340;
    int ans = 1;
    while (lo <= hi) {
        int mid = (lo + hi) >> 1;
        int sq = mid * mid;
        if (sq == n) {
            return mid;
        }
        if (sq < n) {
            ans = mid;
            lo = mid + 1;
        } else {
            hi = mid - 1;
        }
    }
    return ans;
}

static inline int luminance_argb(int argb) {
    unsigned int px = (unsigned int)argb;
    int r = (int)((px >> 16) & 0xFFu);
    int g = (int)((px >> 8) & 0xFFu);
    int b = (int)(px & 0xFFu);
    return (77 * r + 150 * g + 29 * b) >> 8;
}

__kernel void blur_5x5(__global const int* src, __global int* dst, int width, int height) {
    int x = (int)get_global_id(0);
    int y = (int)get_global_id(1);
    if (x >= width || y >= height) {
        return;
    }

    const int weights[5] = {1, 4, 6, 4, 1};
    int maxX = width - 1;
    int maxY = height - 1;

    int rSum = 0;
    int gSum = 0;
    int bSum = 0;

    for (int dy = -2; dy <= 2; dy++) {
        int sy = clamp_coord(y + dy, maxY);
        int sRow = sy * width;
        int wy = weights[dy + 2];
        for (int dx = -2; dx <= 2; dx++) {
            int sx = clamp_coord(x + dx, maxX);
            int weight = wy * weights[dx + 2];
            unsigned int p = (unsigned int)src[sRow + sx];
            rSum += (int)((p >> 16) & 0xFFu) * weight;
            gSum += (int)((p >> 8) & 0xFFu) * weight;
            bSum += (int)(p & 0xFFu) * weight;
        }
    }

    int idx = y * width + x;
    unsigned int alpha = ((unsigned int)src[idx]) & 0xFF000000u;
    unsigned int r = (unsigned int)((rSum + 128) >> 8);
    unsigned int g = (unsigned int)((gSum + 128) >> 8);
    unsigned int b = (unsigned int)((bSum + 128) >> 8);
    dst[idx] = (int)(alpha | (r << 16) | (g << 8) | b);
}

__kernel void sharpen_3x3(__global const int* src, __global int* dst, int width, int height) {
    int x = (int)get_global_id(0);
    int y = (int)get_global_id(1);
    if (x >= width || y >= height) {
        return;
    }

    int maxX = width - 1;
    int maxY = height - 1;

    int rowOffset = y * width;
    int topRow = clamp_coord(y - 1, maxY) * width;
    int botRow = clamp_coord(y + 1, maxY) * width;
    int xm1 = clamp_coord(x - 1, maxX);
    int xp1 = clamp_coord(x + 1, maxX);

    int idx = rowOffset + x;
    unsigned int c = (unsigned int)src[idx];
    unsigned int top = (unsigned int)src[topRow + x];
    unsigned int bot = (unsigned int)src[botRow + x];
    unsigned int left = (unsigned int)src[rowOffset + xm1];
    unsigned int right = (unsigned int)src[rowOffset + xp1];

    unsigned int r = (unsigned int)clamp255(
            5 * (int)((c >> 16) & 0xFFu)
            - (int)((top >> 16) & 0xFFu)
            - (int)((bot >> 16) & 0xFFu)
            - (int)((left >> 16) & 0xFFu)
            - (int)((right >> 16) & 0xFFu));
    unsigned int g = (unsigned int)clamp255(
            5 * (int)((c >> 8) & 0xFFu)
            - (int)((top >> 8) & 0xFFu)
            - (int)((bot >> 8) & 0xFFu)
            - (int)((left >> 8) & 0xFFu)
            - (int)((right >> 8) & 0xFFu));
    unsigned int b = (unsigned int)clamp255(
            5 * (int)(c & 0xFFu)
            - (int)(top & 0xFFu)
            - (int)(bot & 0xFFu)
            - (int)(left & 0xFFu)
            - (int)(right & 0xFFu));

    unsigned int alpha = c & 0xFF000000u;
    dst[idx] = (int)(alpha | (r << 16) | (g << 8) | b);
}

__kernel void sobel_3x3(__global const int* src, __global int* dst, int width, int height) {
    int x = (int)get_global_id(0);
    int y = (int)get_global_id(1);
    if (x >= width || y >= height) {
        return;
    }

    int maxX = width - 1;
    int maxY = height - 1;

    int rowOffset = y * width;
    int ym1Row = clamp_coord(y - 1, maxY) * width;
    int yp1Row = clamp_coord(y + 1, maxY) * width;
    int xm1 = clamp_coord(x - 1, maxX);
    int xp1 = clamp_coord(x + 1, maxX);

    int l00 = luminance_argb(src[ym1Row + xm1]);
    int l01 = luminance_argb(src[ym1Row + x]);
    int l02 = luminance_argb(src[ym1Row + xp1]);

    int l10 = luminance_argb(src[rowOffset + xm1]);
    int l12 = luminance_argb(src[rowOffset + xp1]);

    int l20 = luminance_argb(src[yp1Row + xm1]);
    int l21 = luminance_argb(src[yp1Row + x]);
    int l22 = luminance_argb(src[yp1Row + xp1]);

    int gx = -l00 + l02 - 2 * l10 + 2 * l12 - l20 + l22;
    int gy = -l00 - 2 * l01 - l02 + l20 + 2 * l21 + l22;

    unsigned int mag = (unsigned int)clamp255(isqrt_int(gx * gx + gy * gy));
    int idx = rowOffset + x;
    unsigned int alpha = ((unsigned int)src[idx]) & 0xFF000000u;
    dst[idx] = (int)(alpha | (mag << 16) | (mag << 8) | mag);
}
