// The cloud field moves inside a fixed circle; no photograph or network asset is needed.
const vertexSource = `
attribute vec2 position;
varying vec2 uv;
void main() { uv = position * .5 + .5; gl_Position = vec4(position, 0., 1.); }
`;
const fragmentSource = `
precision highp float;
varying vec2 uv;
uniform float time, level, cancel, quiet;
float hash(vec2 p) {
  p = fract(p * vec2(123.34, 456.21));
  p += dot(p, p + 45.32);
  return fract(p.x * p.y);
}
float noise(vec2 p) {
  vec2 i = floor(p), f = fract(p);
  f = f * f * (3. - 2. * f);
  return mix(mix(hash(i), hash(i + vec2(1., 0.)), f.x),
             mix(hash(i + vec2(0., 1.)), hash(i + vec2(1., 1.)), f.x), f.y);
}
float cloud(vec2 p) {
  float n = 0., weight = .5;
  for (int i = 0; i < 4; i++) {
    n += weight * noise(p);
    p = mat2(.8, -.6, .6, .8) * p * 2.03 + 7.1;
    weight *= .5;
  }
  return n;
}
void main() {
  vec2 p = vec2(uv.x, 1. - uv.y);
  float t = time;
  vec2 flow = vec2(cloud(p * 2.8 + vec2(t * .07, -t * .04)),
                   cloud(p * 3.1 + vec2(-t * .05, t * .03) + 8.));
  float billow = cloud(p * vec2(4.2, 5.5) + flow * 2.5 + vec2(t * .12, -t * .06));
  float detail = cloud(p * 13. + flow * 3. + vec2(t * .08, 0.));
  // A diagonal white current: blue at the upper/left edge, milk at the middle/right.
  float horizon = p.y + p.x * .31 - .61 + (billow - .46) * .32;
  float mist = smoothstep(-.13, .18, horizon);
  float bottom = smoothstep(.65, 1., p.y);
  vec3 blue = mix(vec3(.23, .44, .91), vec3(.43, .61, .96), p.y);
  blue += (billow - .46) * .05;
  vec3 milk = mix(vec3(.94, .96, 1.), vec3(.71, .78, .97), bottom * .8);
  vec3 color = mix(blue, milk, mist);
  float lace = (detail - .46) * .14 * (1. - abs(mist * 2. - 1.));
  color += lace + level * .018;
  vec3 rose = mix(vec3(.79, .31, .35), vec3(.99, .88, .88), mist);
  rose = mix(rose, vec3(.91, .68, .73), bottom * .25) + lace;
  color = mix(color, rose, cancel);
  color = mix(color, vec3(.70, .77, .88), quiet * .38);
  gl_FragColor = vec4(color, 1.);
}
`;

export function createVoiceOrb(canvas) {
  let gl;
  try { gl = canvas.getContext('webgl', {alpha:false, antialias:false, depth:false, stencil:false, preserveDrawingBuffer:true}); }
  catch { return null; }
  if (!gl) return null;
  const shaders = [], program = gl.createProgram(), buffer = gl.createBuffer();
  function dispose() {
    canvas.removeEventListener('webglcontextlost', lost);
    gl.deleteBuffer(buffer); gl.deleteProgram(program);
    for (const shader of shaders) gl.deleteShader(shader);
    gl.getExtension('WEBGL_lose_context')?.loseContext();
  }
  function lost(event) { event.preventDefault(); canvas.hidden = true; }
  try {
    for (const [type, source] of [[gl.VERTEX_SHADER, vertexSource], [gl.FRAGMENT_SHADER, fragmentSource]]) {
      const shader = gl.createShader(type); shaders.push(shader);
      gl.shaderSource(shader, source); gl.compileShader(shader);
      if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) throw Error(gl.getShaderInfoLog(shader));
      gl.attachShader(program, shader);
    }
    gl.linkProgram(program);
    if (!gl.getProgramParameter(program, gl.LINK_STATUS)) throw Error(gl.getProgramInfoLog(program));
    gl.useProgram(program); gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1,-1, 1,-1, -1,1, 1,1]), gl.STATIC_DRAW);
    const position = gl.getAttribLocation(program, 'position');
    gl.enableVertexAttribArray(position); gl.vertexAttribPointer(position, 2, gl.FLOAT, false, 0, 0);
    const uniforms = Object.fromEntries(['time','level','cancel','quiet'].map(name => [name, gl.getUniformLocation(program, name)]));
    // Cap the texture size while ASR is running on the same phone.
    canvas.width = canvas.height = Math.min(320, Math.ceil((canvas.clientWidth || 170) * Math.min(devicePixelRatio || 1, 1.5)));
    gl.viewport(0, 0, canvas.width, canvas.height);
    canvas.addEventListener('webglcontextlost', lost);
    function draw(time=0, level=0, cancel=0, quiet=0) {
      if (gl.isContextLost()) return;
      for (const [name, value] of Object.entries({time, level, cancel, quiet})) gl.uniform1f(uniforms[name], value);
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    }
    draw();
    canvas.hidden = false;
    return {draw, dispose};
  } catch {
    dispose(); canvas.hidden = true; return null;
  }
}
