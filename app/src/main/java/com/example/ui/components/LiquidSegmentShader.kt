package com.example.ui.components

/** Native AGSL equivalent of the supplied timer's fragment shader. No WebView/GL surface. */
internal const val LIQUID_SEGMENT_SHADER = """
uniform shader track;
uniform shader labels;
uniform float2 resolution;
uniform float density;
uniform float leftEdge, rightEdge, velocity, wobble, press;
uniform float3 accent, ink, glow;
uniform float3 satellite0, satellite1, satellite2;
uniform float slotWidth, lip;
uniform float selectionActive;

float boxDistance(float2 q, float2 b, float r) {
    float2 d = abs(q) - b + r;
    return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0) - r;
}
float smoothMinimum(float a, float b, float k) {
    float h = max(k - abs(a - b), 0.0) / k;
    return min(a, b) - h * h * k * .25;
}
float satelliteDistance(float2 p, float3 s) {
    return s.z < .4 ? 1000.0 : length(p - s.xy) - s.z;
}
float shape(float2 p) {
    float cx = (leftEdge + rightEdge) * .5;
    float hw = max((rightEdge - leftEdge) * .5, 1.0);
    float dir = clamp(velocity / 1000.0, -1.0, 1.0);
    float hh = (resolution.y - 8.0) * .5 * (1.0 + press * .09) * (1.0 - abs(dir) * .16) * (1.0 - abs(wobble) * .12);
    float2 q = p - float2(cx, resolution.y * .5);
    float disturbance = min(abs(velocity) / 1800.0, 1.0) * 5.0 + min(abs(wobble) * 9.0, 4.0);
    float phase = cx * .045;
    q.y += disturbance * sin(q.x * .11 + phase);
    q.x += disturbance * .6 * sin(q.y * .22 + phase * 1.3);
    float qn = clamp(q.x / hw, -1.0, 1.0);
    float tail = clamp(-dir * qn, 0.0, 1.0);
    float h2 = max(1.0, hh * (1.0 + dir * .24 * qn + wobble * .70 * qn) * (1.0 - .50 * abs(dir) * tail * tail));
    q.y -= wobble * 4.0 * qn;
    float d = boxDistance(q, float2(hw, h2), h2);
    d = smoothMinimum(d, satelliteDistance(p, satellite0), 11.0);
    d = smoothMinimum(d, satelliteDistance(p, satellite1), 11.0);
    return smoothMinimum(d, satelliteDistance(p, satellite2), 9.0);
}
// Background and glyph masks are cached. Position-dependent lighting/color runs on the GPU.
float3 sampleBackground(float2 p) {
    float cx = (leftEdge + rightEdge) * .5;
    half4 trackColor = track.eval(p * density);
    float3 base = float3(trackColor.rgb);
    float light = max(0.0, 1.0 - length((p - float2(cx, resolution.y * .6)) / float2(80.0, 80.0)));
    base = mix(base, glow, selectionActive * light * (.20 + min(abs(velocity) / 1800.0, 1.0) * .25));
    float slotCenter = 4.0 + (floor(clamp((p.x - 4.0) / slotWidth, 0.0, 100.0)) + .5) * slotWidth;
    float proximity = max(0.0, 1.0 - abs(cx - slotCenter) / (slotWidth * .8));
    float tint = selectionActive * proximity * proximity * (3.0 - 2.0 * proximity);
    half4 labelColor = labels.eval(p * density);
    float alpha = float(labelColor.a);
    return mix(base, mix(ink, accent, tint), alpha);
}
half4 main(float2 pixel) {
    float2 p = pixel / density;
    float cy = resolution.y * .5;
    float dTrack = boxDistance(p - float2(resolution.x * .5, cy), resolution * .5, cy);
    float mask = 1.0 - smoothstep(-.7, .7, dTrack);
    float4 b = float4(sampleBackground(p) * mask, mask);
    float bottomLip = (1.0 - smoothstep(0.0, 1.4, dTrack)) * step(0.0, dTrack) * step(cy, p.y) * lip;
    b += float4(float3(bottomLip), bottomLip) * (1.0 - b.a);
    if (selectionActive < .5) return half4(b);
    float dir = clamp(velocity / 1000.0, -1.0, 1.0);
    float2 off = float2(-dir * 9.0, 7.0 + 3.0 * press);
    float sd = shape(p - off);
    float shadow = (1.0 - smoothstep(-5.0, 9.0 + 4.0 * press + abs(dir) * 5.0, sd)) * (.20 - .04 * press);
    float core = (1.0 - smoothstep(-2.0, 5.0, sd + 9.0)) * .28;
    float4 base = b * (1.0 - shadow) + float4(float3(.22, .09, .05) * shadow, shadow);
    base.rgb += glow * core * .75 * mask;
    base.a = max(base.a, core * .45 * mask);
    float d = shape(p);
    if (d > 1.5) return half4(base);
    float e = .75;
    float2 n = normalize(float2(shape(p + float2(e, 0.0)) - shape(p - float2(e, 0.0)),
                                shape(p + float2(0.0, e)) - shape(p - float2(0.0, e))) + .00001);
    float B = (resolution.y - 8.0) * .30;
    float t = clamp(-d / B, 0.0, 1.0);
    float edge = 1.0 - t;
    float z = sqrt(max(1.0 - edge * edge, 0.0));
    float2 c = float2((leftEdge + rightEdge) * .5, cy);
    float2 displacement = (c - p) * .20 + n * (17.0 * edge * edge);
    float3 col = float3(sampleBackground(p + displacement * .925).r,
                       sampleBackground(p + displacement).g,
                       sampleBackground(p + displacement * 1.075).b);
    float dropAlpha = mix(.62, 1.0, mask);
    float4 refracted = float4(col * dropAlpha, dropAlpha);
    float3 n3 = normalize(float3(n * pow(edge, .8) * 1.3, .6 + .6 * z));
    float3 view = float3(0.0, 0.0, 1.0);
    float s1 = pow(max(dot(n3, normalize(normalize(float3(-.5, -.7, .55)) + view)), 0.0), 30.0) * .85;
    float s2 = pow(max(dot(n3, normalize(normalize(float3(.5, .7, .55)) + view)), 0.0), 22.0) * .30;
    float bottom = smoothstep(.15, 1.0, n.y) * edge * edge * .30;
    float line = (1.0 - smoothstep(0.0, 1.6, -d)) * .20;
    refracted.rgb *= 1.0 - .10 * pow(edge, 3.0);
    refracted.rgb += float3(1.0, .97, .94) * (s1 + s2 + line) + glow * bottom;
    refracted.a = max(refracted.a, max(refracted.r, max(refracted.g, refracted.b)));
    return half4(mix(base, refracted, 1.0 - smoothstep(-.8, .8, d)));
}
"""
