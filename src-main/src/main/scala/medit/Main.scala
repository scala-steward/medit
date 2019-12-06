package medit

import java.io.File

import org.lwjgl._
import org.lwjgl.glfw._
import org.lwjgl.opengl._
import org.lwjgl.system._
import java.nio._

import medit.draw.{DrawCall, Position, TextStyle, Typeface}
import org.lwjgl.glfw.Callbacks._
import org.lwjgl.glfw.GLFW._
import org.lwjgl.opengl.GL30._
import org.lwjgl.opengl.GL11._
import org.lwjgl.opengl.GL32C._
import org.lwjgl.system.MemoryStack._
import org.lwjgl.system.MemoryUtil._
import org.bytedeco.javacpp.Pointer
import org.bytedeco.skia._
import org.bytedeco.skia.global.Skia._
import utils._


object Main {
  def main(args: Array[String]): Unit = {
    new Window()
  }
}

class Window {
  private var window = 0L

  lazy val glinterface = gr_glinterface_create_native_interface()

  var windowSize: (Int, Int) = (500, 500)
  var frameBufferSize: (Int, Int) = null
  def calculateDp(): Unit = {
    dp = 1f * frameBufferSize._1 / windowSize._1
  }
  var dp: Float = 1

  var gr_context: gr_context_t = null
  var gr_ffi: gr_gl_framebufferinfo_t = null
  var gr_backend: gr_backendrendertarget_t = null
  var surface: sk_surface_t = null
  var canvas: sk_canvas_t = null

  GLFWErrorCallback.createPrint(System.err).set
  if (!glfwInit) throw new IllegalStateException("Unable to initialize GLFW")
  glfwDefaultWindowHints()
  glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
  glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE)
  glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
  glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2)
  glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GL_TRUE)
  glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
  glfwWindowHint(GLFW_SRGB_CAPABLE, GL_TRUE)
  glfwWindowHint(GLFW_STENCIL_BITS, 0)
  //glfwWindowHint(GLFW_ALPHA_BITS, 0)
  glfwWindowHint(GLFW_DEPTH_BITS, 0)
  window = glfwCreateWindow(600, 600, "medit", NULL, NULL)
  if (window == NULL) throw new RuntimeException("Failed to create the GLFW window")
  glfwSetKeyCallback(window, (window: Long, key: Int, scancode: Int, action: Int, mods: Int) => {
  })
  // Get the thread stack and push a new frame
  val stack = stackPush
  try {
    val pWidth = stack.mallocInt(1) // int*
    val pHeight = stack.mallocInt(1)
    glfwGetWindowSize(window, pWidth, pHeight)
    debug(s"window size ${pWidth.get(0)}, ${pHeight.get(0)}")
    val vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor)
    glfwSetWindowPos(window, (vidmode.width - pWidth.get(0)) / 2, (vidmode.height - pHeight.get(0)) / 2)
    glfwGetFramebufferSize(window, pWidth, pHeight)
    debug(s"frame buffer size ${pWidth.get(0)}, ${pHeight.get(0)}")
    frameBufferSize = (pWidth.get(0), pHeight.get(0))
    calculateDp()
  } finally {
    if (stack != null) stack.close()
  }
  glfwSetWindowSizeCallback(window, (window, width, height) => {
    windowSize = (width, height)
    calculateDp()
  })
  glfwSetFramebufferSizeCallback(window, (window, width, height) => {
    debug(s"frame buffer size changed $width $height")
    frameBufferSize = (width, height)
    calculateDp()
    glfwMakeContextCurrent(window)
    glViewport(0, 0, width, height)
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT)
    destroySurface()
    createSkSurface()
    render()
  })
  // TODO resizing window will cause drawing to turn black
  glfwMakeContextCurrent(window)
  GL.createCapabilities
  glEnable(GL_BLEND)
  glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
  glEnable(GL_FRAMEBUFFER_SRGB)
  // GrContextOptions options;
  //options.fRequireDecodeDisableForSRGB = false; //was removed?
  gr_context = gr_context_make_gl(glinterface)
  createSkSurface()
  glfwSwapInterval(1)
  glfwShowWindow(window)
  //glClearColor(1.0f, 0.0f, 0.0f, 0.0f)
  glViewport(0, 0, frameBufferSize._1, frameBufferSize._2)
  medit.draw.impl = new Impl()
  paints = new Paints()
  while (!glfwWindowShouldClose(window)) {
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT)
    render()
    gr_context_flush(gr_context)
    glfwSwapBuffers(window)
    glfwPollEvents()
  }
  destroySurface()
  gr_context_release_resources_and_abandon_context(gr_context)
  // Free the window callbacks and destroy the window
  glfwFreeCallbacks(window)
  glfwDestroyWindow(window)
  // Terminate GLFW and free the error callback
  glfwTerminate()
  glfwSetErrorCallback(null).free()


  def destroySurface(): Unit = {
    sk_surface_unref(surface)
    gr_backendrendertarget_delete(gr_backend)
    gr_ffi.deallocate()
  }

  def createSkSurface(): Unit = {
    gr_ffi = new gr_gl_framebufferinfo_t()
    gr_ffi.fFBOID(0)
    gr_ffi.fFormat(GL_RGBA8)
    val colorType = RGBA_8888_SK_COLORTYPE
    //    if (kRGBA_8888_GrPixelConfig == kSkia8888_GrPixelConfig)
    //      kRGBA_8888_SkColorType;
    //      else kBGRA_8888_SkColorType;
    gr_backend = gr_backendrendertarget_new_gl(frameBufferSize._1, frameBufferSize._2, 0, 0, gr_ffi)
    surface = sk_surface_new_backend_render_target(
      gr_context,
      gr_backend,
      BOTTOM_LEFT_GR_SURFACE_ORIGIN,
      colorType,
      null,
      null
    )
    canvas = sk_surface_get_canvas(surface)
  }

  object typefaces {
    def font(a: String, index: Int): sk_typeface_t = sk_typeface_create_from_file(new File(new File("fonts"), a).getPath, index)
    def apply(a: medit.draw.Typeface): sk_typeface_t = a match {
      case Typeface.Mono => Mono
    }
    val Mono = font("Menlo.ttc", 0)
  }

  class Impl() extends medit.draw.Impl {
    override def measure(textStyle: TextStyle, str: String): Int = {
      0
    }
  }


  class Paints() {
    val text : sk_paint_t = sk_paint_new()
    sk_paint_set_antialias(text, true)
  }

  var paints: Paints = null

  private def draw(text: DrawCall.Text): Unit = {
    val paint = paints.text
    sk_paint_set_color(paint, text.style.color)
    sk_paint_set_textsize(paint, text.style.size * dp)
    sk_paint_set_typeface(paint, typefaces(text.style.typeface))
    sk_canvas_draw_text(canvas, text.text, text.text.size, text.position.left * dp, text.position.top * dp, paint)
  }

  private def render() = {
    val fill = sk_paint_new()
    sk_paint_set_color(fill, 0xFF0000FF)
    sk_canvas_draw_paint(canvas, fill)
    sk_paint_set_color(fill, 0xFF00FFFF)
    val rect = new sk_rect_t
    rect.left(100.0f)
    rect.top(100.0f)
    rect.right(540.0f)
    rect.bottom(380.0f)
    sk_canvas_draw_rect(canvas, rect, fill)
    val stroke = sk_paint_new()
    sk_paint_set_color(stroke, 0xFFFF0000)
    sk_paint_set_antialias(stroke, true)
    sk_paint_set_style(stroke, STROKE_SK_PAINT_STYLE)
    sk_paint_set_stroke_width(stroke, 5.0f)
    val path = sk_path_new
    sk_path_move_to(path, 50.0f, 50.0f)
    sk_path_line_to(path, 590.0f, 50.0f)
    sk_path_cubic_to(path, -490.0f, 50.0f, 1130.0f, 430.0f, 50.0f, 430.0f)
    sk_path_line_to(path, 590.0f, 430.0f)
    sk_canvas_draw_path(canvas, path, stroke)
    sk_paint_set_color(fill, 0x8000FF00)
    val rect2 = new sk_rect_t
    rect2.left(120.0f)
    rect2.top(120.0f)
    rect2.right(520.0f)
    rect2.bottom(360.0f)
    sk_canvas_draw_oval(canvas, rect2, fill)
    draw(DrawCall.Text(Position(100, 100, 0), TextStyle(0xFFFF0000, Typeface.Mono, 13), "sk_paint_set_color"))
    sk_path_delete(path)
    sk_paint_delete(stroke)
    sk_paint_delete(fill)
  }
}