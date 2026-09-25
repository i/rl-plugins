#version 330

// A full-screen triangle generated from the vertex index, drawn with an empty VAO. UVs cover 0..1 across the screen.
out vec2 fUv;

void main() {
    vec2 uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    fUv = uv;
    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
}
