#version 330 core
// Restores framebuffer alpha to 1 after the star pass used it as a visibility mask.
out vec4 fragColor;

void main() {
    fragColor = vec4(0.0, 0.0, 0.0, 1.0);
}
