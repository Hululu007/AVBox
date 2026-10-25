package com.github.avbox.osc.player.effect.anime4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class Anime4kShaderTest {

    private val sample = """
        // MIT License sample
        //!DESC pass-one
        //!HOOK MAIN
        //!BIND MAIN
        //!SAVE conv2d_tf
        //!WIDTH MAIN.w
        //!HEIGHT MAIN.h
        //!COMPONENTS 4
        #define go_0(x_off, y_off) (MAIN_texOff(vec2(x_off, y_off)))
        vec4 hook() {
            vec4 result = mat4(0.5) * go_0(0.0, 0.0);
            return result + MAIN_tex(MAIN_pos);
        }
        //!DESC pass-two
        //!HOOK MAIN
        //!BIND MAIN
        //!BIND conv2d_tf
        //!SAVE MAIN
        //!WIDTH conv2d_tf.w 2 *
        //!HEIGHT conv2d_tf.h 2 *
        //!WHEN OUTPUT.w MAIN.w / 1.200 >
        vec4 hook() {
            return MAIN_tex(MAIN_pos) + conv2d_tf_tex(conv2d_tf_pos);
        }
    """.trimIndent()

    private fun sizeOf(sizes: Map<String, IntArray>, width: Boolean) = { name: String ->
        sizes[name]?.get(if (width) 0 else 1) ?: 0
    }

    @Test
    fun parse_reads_directives_and_pass_shape() {
        val passes = Anime4kShader.parse(sample)
        assertEquals(2, passes.size)

        assertEquals("pass-one", passes[0].name)
        assertEquals(listOf("MAIN"), passes[0].binds)
        assertEquals("conv2d_tf", passes[0].save)
        assertEquals(4, passes[0].components)
        assertEquals(emptyList<String>(), passes[0].whenExpr)

        assertEquals("pass-two", passes[1].name)
        assertEquals(listOf("MAIN", "conv2d_tf"), passes[1].binds)
        assertEquals("MAIN", passes[1].save)
        assertEquals(listOf("OUTPUT.w", "MAIN.w", "/", "1.200", ">"), passes[1].whenExpr)
    }

    @Test
    fun resolveSize_evaluates_rpn_size_expressions() {
        val passes = Anime4kShader.parse(sample)
        val sizes = mapOf(
            "MAIN" to intArrayOf(1920, 1080),
            "conv2d_tf" to intArrayOf(1920, 1080),
        )
        assertEquals(1920, Anime4kShader.resolveSize(passes[0].widthExpr, sizeOf(sizes, true), sizeOf(sizes, false)))
        assertEquals(1080, Anime4kShader.resolveSize(passes[0].heightExpr, sizeOf(sizes, true), sizeOf(sizes, false)))
        assertEquals(3840, Anime4kShader.resolveSize(passes[1].widthExpr, sizeOf(sizes, true), sizeOf(sizes, false)))
        assertEquals(2160, Anime4kShader.resolveSize(passes[1].heightExpr, sizeOf(sizes, true), sizeOf(sizes, false)))
    }

    @Test
    fun generated_shader_keeps_body_and_defines_mpv_macros() {
        val shader = Anime4kShader.parse(sample).first().fragmentShader
        assertTrue(shader.contains("#define MAIN_tex(p) texture2D(uTexMAIN, (p))"))
        assertTrue(shader.contains("#define MAIN_texOff(off) texture2D(uTexMAIN, vTexCoord + (off) * uPtMAIN)"))
        assertTrue(shader.contains("#define MAIN_pos vTexCoord"))
        assertTrue(shader.contains("#define MAIN_pt uPtMAIN"))
        assertTrue(shader.contains("#define MAIN_size uSizeMAIN"))
        assertTrue(shader.contains("uniform sampler2D uTexMAIN;"))
        assertTrue(shader.contains("varying vec2 vTexCoord;"))
        assertTrue(shader.contains("vec4 hook() {"))
        assertTrue(shader.contains("void main() {"))
        assertTrue(shader.contains("gl_FragColor = hook();"))
        assertTrue(shader.contains("mat4(0.5)"))
        assertFalse(shader.contains("//!"))
    }

    @Test
    fun real_restore_cnn_s_parses_into_four_passes() {
        val file = listOf(
            File("src/main/assets/Anime4K/Anime4K_Restore_CNN_S.glsl"),
            File("app/src/main/assets/Anime4K/Anime4K_Restore_CNN_S.glsl"),
        ).firstOrNull { it.exists() }
        assumeTrue("未找到资产文件,跳过", file != null)
        val passes = Anime4kShader.parse(file!!.readText())
        assertEquals(4, passes.size)
        assertEquals(listOf("MAIN"), passes[0].binds)
        assertEquals("conv2d_tf", passes[0].save)
        assertEquals("conv2d_1_tf", passes[1].save)
        assertEquals("conv2d_2_tf", passes[2].save)
        assertEquals("MAIN", passes[3].save)
        assertEquals(listOf("MAIN", "conv2d_2_tf"), passes[3].binds)
        assertTrue(passes.all { it.whenExpr.isEmpty() })
        assertTrue(passes.all { it.components == 4 })
        assertTrue(passes.all { it.fragmentShader.contains("void main()") })
        assertTrue(passes.all { !it.fragmentShader.contains("//!") })
    }

    private fun asset(name: String): File? = listOf(
        File("src/main/assets/Anime4K/$name"),
        File("app/src/main/assets/Anime4K/$name"),
    ).firstOrNull { it.exists() }

    @Test
    fun depth_to_space_is_rewritten_for_es2() {
        listOf(
            "Anime4K_Upscale_CNN_x2_S.glsl" to 5,
            "Anime4K_Upscale_CNN_x2_M.glsl" to 9,
            "Anime4K_Upscale_CNN_x2_L.glsl" to 10,
        ).forEach { (name, passCount) ->
            val file = asset(name)
            assumeTrue("未找到 $name,跳过", file != null)
            val passes = Anime4kShader.parse(file!!.readText())
            assertEquals(name, passCount, passes.size)
            val last = passes.last()
            assertEquals(name, "MAIN", last.save)
            assertTrue("$name 末 pass 应出 2x", last.widthExpr.contains("*"))
            assertFalse("$name 残留 ivec2", last.fragmentShader.contains("ivec2"))
            assertFalse("$name 残留动态下标 i0", last.fragmentShader.contains("[i0."))
            assertFalse("$name 残留动态下标 i1", last.fragmentShader.contains("[i1."))
            assertFalse("$name 残留动态下标 i2", last.fragmentShader.contains("[i2."))
            assertTrue("$name 缺 step 选择", last.fragmentShader.contains("step(0.5,"))
            assertTrue("$name 缺 mix 选择", last.fragmentShader.contains("mix(mix("))
            assertTrue("$name 丢了原采样表达式", last.fragmentShader.contains("_tex((vec2(0.5) - f0) *"))
        }
    }

    @Test
    fun plan_chains_restore_then_upscale_and_presents_at_source_size() {
        val restore = asset("Anime4K_Restore_CNN_S.glsl")
        val upscale = asset("Anime4K_Upscale_CNN_x2_S.glsl")
        assumeTrue("未找到资产文件,跳过", restore != null && upscale != null)
        val planned = Anime4kShader.plan(listOf(restore!!.readText(), upscale!!.readText()), 1920, 1080)
        assertEquals(10, planned.size)
        assertEquals("MAIN", planned[3].pass.save)
        assertFalse("串联时中间那次 SAVE MAIN 不能直接出屏", planned[3].writesScreen)
        assertEquals("present", planned.last().pass.name)
        assertTrue(planned.last().writesScreen)
        assertEquals(1920, planned.last().width)
        assertEquals(1080, planned.last().height)
        assertEquals(3840, planned[8].width)
        assertEquals(2160, planned[8].height)
        assertFalse("2x 那一 pass 不该直接出屏", planned[8].writesScreen)
        val presented = planned.last().bindings.first { it.name == "MAIN" }
        assertEquals(3840, presented.width)
        assertEquals(2160, presented.height)
        assertEquals(1920, planned[4].width)
        assertEquals(1920, planned[4].bindings.first { it.name == "MAIN" }.width)
    }

    @Test
    fun plan_of_ultra_tier_presents_back_to_source_size_after_nineteen_passes() {
        val restore = asset("Anime4K_Restore_CNN_L.glsl")
        val upscale = asset("Anime4K_Upscale_CNN_x2_L.glsl")
        assumeTrue("未找到资产文件,跳过", restore != null && upscale != null)
        val planned = Anime4kShader.plan(listOf(restore!!.readText(), upscale!!.readText()), 1920, 1080)
        assertEquals(20, planned.size)
        assertEquals(3840, planned[18].width)
        assertEquals(2160, planned[18].height)
        assertTrue(planned.last().writesScreen)
        assertEquals(1920, planned.last().width)
        assertEquals(1080, planned.last().height)
        assertTrue(planned.dropLast(1).none { it.writesScreen })
    }

    @Test
    fun plan_uses_canvas_as_output_when_aspect_matches() {
        val restore = asset("Anime4K_Restore_CNN_S.glsl")
        val upscale = asset("Anime4K_Upscale_CNN_x2_S.glsl")
        assumeTrue("未找到资产文件,跳过", restore != null && upscale != null)
        val planned = Anime4kShader.plan(
            listOf(restore!!.readText(), upscale!!.readText()),
            1920,
            1080,
            intArrayOf(2240, 1260),
        )
        assertEquals(10, planned.size)
        val present = planned.last()
        assertEquals("present", present.pass.name)
        assertEquals(2240, present.width)
        assertEquals(1260, present.height)
        assertTrue(present.writesScreen)
        assertEquals(3840, present.bindings.first { it.name == "MAIN" }.width)
        assertTrue(present.pass.fragmentShader.contains("vec2(-0.5, -0.5)"))
        assertTrue(planned.dropLast(1).none { it.writesScreen })
    }

    @Test
    fun plan_uses_linear_present_for_a_mild_canvas_downscale() {
        val restore = asset("Anime4K_Restore_CNN_S.glsl")
        val upscale = asset("Anime4K_Upscale_CNN_x2_S.glsl")
        assumeTrue("未找到资产文件,跳过", restore != null && upscale != null)
        val planned = Anime4kShader.plan(
            listOf(restore!!.readText(), upscale!!.readText()),
            1280,
            720,
            intArrayOf(2240, 1260),
        )
        val present = planned.last()
        assertEquals(2240, present.width)
        assertEquals(1260, present.height)
        assertEquals(2560, present.bindings.first { it.name == "MAIN" }.width)
        assertTrue(present.pass.fragmentShader.contains("uniform float uSharpen;"))
        assertTrue(present.pass.fragmentShader.contains("dot(c.rgb, LUMA)"))
        assertTrue(present.pass.fragmentShader.contains("clamp(delta, -down, up)"))
        assertFalse(present.pass.fragmentShader.contains("vec2(-0.5, -0.5)"))
    }

    @Test
    fun plan_falls_back_to_source_size_when_canvas_aspect_differs() {
        val restore = asset("Anime4K_Restore_CNN_S.glsl")
        val upscale = asset("Anime4K_Upscale_CNN_x2_S.glsl")
        assumeTrue("未找到资产文件,跳过", restore != null && upscale != null)
        val planned = Anime4kShader.plan(
            listOf(restore!!.readText(), upscale!!.readText()),
            1920,
            1080,
            intArrayOf(2800, 1260),
        )
        assertEquals(1920, planned.last().width)
        assertEquals(1080, planned.last().height)
    }

    @Test
    fun plan_falls_back_to_source_size_when_canvas_is_smaller() {
        val restore = asset("Anime4K_Restore_CNN_S.glsl")
        val upscale = asset("Anime4K_Upscale_CNN_x2_S.glsl")
        assumeTrue("未找到资产文件,跳过", restore != null && upscale != null)
        val planned = Anime4kShader.plan(
            listOf(restore!!.readText(), upscale!!.readText()),
            1920,
            1080,
            intArrayOf(1260, 709),
        )
        assertEquals(1920, planned.last().width)
        assertEquals(1080, planned.last().height)
    }

    @Test
    fun plan_uses_canvas_for_one_to_one_tier_too() {
        val restore = asset("Anime4K_Restore_CNN_S.glsl")
        assumeTrue("未找到资产文件,跳过", restore != null)
        val planned = Anime4kShader.plan(
            listOf(restore!!.readText()),
            1920,
            1080,
            intArrayOf(2240, 1260),
        )
        assertEquals(5, planned.size)
        assertEquals("present", planned.last().pass.name)
        assertEquals(2240, planned.last().width)
        assertEquals(1260, planned.last().height)
        assertTrue(planned.last().writesScreen)
    }

    private fun passesOf(name: String): List<Anime4kPass>? = asset(name)?.readText()?.let(Anime4kShader::parse)

    @Test
    fun generated_shader_drops_bindings_the_body_never_uses() {
        val clamp = passesOf("Anime4K_Clamp_Highlights.glsl")
        assumeTrue("未找到资产文件,跳过", clamp != null)
        val stats = clamp!![1]
        assertFalse("未用到的 BIND 不该留", stats.binds.contains(Anime4kShader.HOOKED))
        assertTrue(stats.binds.contains("STATSMAX"))
        assertFalse(stats.fragmentShader.contains("uTexHOOKED"))
        assertTrue(stats.fragmentShader.contains("uTexSTATSMAX"))
    }

    @Test
    fun generated_shader_aliases_hooked_and_main() {
        val clamp = passesOf("Anime4K_Clamp_Highlights.glsl")
        assumeTrue("未找到资产文件,跳过", clamp != null)
        val stats = clamp!!.first()
        assertTrue(stats.fragmentShader.contains("#define HOOKED_texOff(off)"))
        assertTrue(stats.fragmentShader.contains("#define MAIN_texOff(off)"))
        assertTrue(stats.fragmentShader.contains("uniform sampler2D uTexHOOKED;"))
        assertFalse("别名不该另开 uniform", stats.fragmentShader.contains("uniform sampler2D uTexMAIN;"))
    }

    @Test
    fun compose_orders_stats_restore_deblur_clamp_then_upscale() {
        val clamp = passesOf("Anime4K_Clamp_Highlights.glsl")
        val restore = passesOf("Anime4K_Restore_CNN_S.glsl")
        val upscale = passesOf("Anime4K_Upscale_CNN_x2_S.glsl")
        val deblur = passesOf("Anime4K_Deblur_DoG.glsl")
        assumeTrue("未找到资产文件,跳过", clamp != null && restore != null && upscale != null && deblur != null)
        val composed = Anime4kShader.compose(
            restore!!,
            upscale!!,
            deblur!!,
            clamp!!,
            1280,
            intArrayOf(2240, 1260),
            true,
        )
        val names = composed.passes.map { it.name }
        assertEquals(1, composed.upscaleCount)
        assertEquals(2, names.count { it.contains("Compute-Statistics") })
        assertEquals(4, names.count { it.contains("Deblur") })
        assertEquals(1, names.count { it.contains("De-Ring-Clamp") })
        assertTrue(
            "Deblur 要在 Clamp 应用之前",
            names.indexOfLast { it.contains("Deblur") } < names.indexOfFirst { it.contains("De-Ring-Clamp") },
        )
        assertTrue(
            "放大要在 Clamp 应用之后(对应 mpv 的 PREKERNEL)",
            names.indexOfFirst { it.contains("Depth-to-Space") } > names.indexOfFirst { it.contains("De-Ring-Clamp") },
        )
    }

    @Test
    fun compose_skips_upscale_when_canvas_is_close_to_source() {
        val restore = passesOf("Anime4K_Restore_CNN_S.glsl")
        val upscale = passesOf("Anime4K_Upscale_CNN_x2_S.glsl")
        val clamp = passesOf("Anime4K_Clamp_Highlights.glsl")
        assumeTrue("未找到资产文件,跳过", restore != null && upscale != null && clamp != null)
        val composed = Anime4kShader.compose(
            restore!!,
            upscale!!,
            emptyList(),
            clamp!!,
            1920,
            intArrayOf(2240, 1260),
            false,
        )
        assertEquals(0, composed.upscaleCount)
        assertTrue(composed.passes.none { it.name.contains("Depth-to-Space") })
    }

    @Test
    fun compose_doubles_upscale_when_source_is_far_below_canvas() {
        val restore = passesOf("Anime4K_Restore_CNN_S.glsl")
        val upscale = passesOf("Anime4K_Upscale_CNN_x2_S.glsl")
        val clamp = passesOf("Anime4K_Clamp_Highlights.glsl")
        assumeTrue("未找到资产文件,跳过", restore != null && upscale != null && clamp != null)
        val composed = Anime4kShader.compose(
            restore!!,
            upscale!!,
            emptyList(),
            clamp!!,
            854,
            intArrayOf(2240, 1260),
            false,
        )
        assertEquals(2, composed.upscaleCount)
        assertEquals(2, composed.passes.count { it.name.contains("Depth-to-Space") })
    }

    @Test
    fun compose_keeps_one_upscale_for_upscale_only_tier_near_canvas() {
        val upscale = passesOf("Anime4K_Upscale_CNN_x2_S.glsl")
        val clamp = passesOf("Anime4K_Clamp_Highlights.glsl")
        assumeTrue("未找到资产文件,跳过", upscale != null && clamp != null)
        val composed = Anime4kShader.compose(
            emptyList(),
            upscale!!,
            emptyList(),
            clamp!!,
            1920,
            intArrayOf(2240, 1260),
            false,
        )
        assertEquals(1, composed.upscaleCount)
        assertTrue(composed.passes.any { it.name.contains("Depth-to-Space") })
    }

    @Test
    fun assignTargets_never_reuses_the_image_a_hooked_pass_reads() {
        val restore = passesOf("Anime4K_Restore_CNN_S.glsl")
        val clamp = passesOf("Anime4K_Clamp_Highlights.glsl")
        assumeTrue("未找到资产文件,跳过", restore != null && clamp != null)
        val composed = Anime4kShader.compose(
            restore!!,
            emptyList(),
            emptyList(),
            clamp!!,
            1280,
            intArrayOf(2240, 1260),
            false,
        )
        val io = Anime4kShader.assignTargets(
            Anime4kShader.planPasses(composed.passes, 1280, 720, intArrayOf(2240, 1260)),
        )
        val apply = io.first { it.planned.pass.name.contains("De-Ring-Clamp") }
        val hooked = apply.bindings.first { it.name == Anime4kShader.HOOKED }
        assertTrue("Clamp 应用不能写进它自己读的那张纹理", apply.outputTarget != hooked.target)
    }

    @Test
    fun plan_uses_linear_present_when_canvas_exceeds_chain_result() {
        val restore = asset("Anime4K_Restore_CNN_S.glsl")
        val upscale = asset("Anime4K_Upscale_CNN_x2_S.glsl")
        assumeTrue("未找到资产文件,跳过", restore != null && upscale != null)
        val planned = Anime4kShader.plan(
            listOf(restore!!.readText(), upscale!!.readText()),
            640,
            360,
            intArrayOf(2240, 1260),
        )
        val present = planned.last()
        assertEquals(2240, present.width)
        assertEquals(1260, present.height)
        assertTrue(present.pass.fragmentShader.contains("uniform float uSharpen;"))
        assertTrue(present.pass.fragmentShader.contains("dot(c.rgb, LUMA)"))
        assertTrue(present.pass.fragmentShader.contains("clamp(delta, -down, up)"))
        assertFalse(present.pass.fragmentShader.contains("vec2(-0.5, -0.5)"))
    }

    @Test
    fun assignTargets_keeps_resave_input_alive_at_a_new_size() {
        val restore = asset("Anime4K_Restore_CNN_S.glsl")
        val upscale = asset("Anime4K_Upscale_CNN_x2_S.glsl")
        assumeTrue("未找到资产文件,跳过", restore != null && upscale != null)
        val io = Anime4kShader.assignTargets(
            Anime4kShader.plan(listOf(restore!!.readText(), upscale!!.readText()), 1920, 1080),
        )
        assertEquals(10, io.size)
        val restoreOut = io[3]
        assertTrue("Restore 末 pass 应新建 MAIN 目标", restoreOut.createdTarget)
        val depthToSpace = io[8]
        assertEquals(3840, depthToSpace.planned.width)
        assertTrue("2x 那一 pass 必须新建目标", depthToSpace.createdTarget)
        val resaveBinding = depthToSpace.bindings.first { it.name == "MAIN" }
        assertEquals(
            "BIND 与 SAVE 同名时必须读旧图,不能读自己的输出",
            restoreOut.outputTarget,
            resaveBinding.target,
        )
        assertTrue(resaveBinding.target != depthToSpace.outputTarget)
        val present = io[9]
        assertEquals(Anime4kShader.SCREEN_TARGET, present.outputTarget)
        assertEquals(depthToSpace.outputTarget, present.bindings.first { it.name == "MAIN" }.target)
    }

    @Test
    fun assignTargets_reuses_same_size_names_across_files() {
        val restore = asset("Anime4K_Restore_CNN_S.glsl")
        val upscale = asset("Anime4K_Upscale_CNN_x2_S.glsl")
        assumeTrue("未找到资产文件,跳过", restore != null && upscale != null)
        val io = Anime4kShader.assignTargets(
            Anime4kShader.plan(listOf(restore!!.readText(), upscale!!.readText()), 1920, 1080),
        )
        val restoreConv = io[0]
        assertTrue(restoreConv.createdTarget)
        val upscaleConv = io[4]
        assertFalse("同尺寸且不读同名 ⇒ 应复用同一张纹理", upscaleConv.createdTarget)
        assertEquals(restoreConv.outputTarget, upscaleConv.outputTarget)
    }

    @Test
    fun assignTargets_binds_input_for_one_to_one_tier() {
        val restore = asset("Anime4K_Restore_CNN_S.glsl")
        assumeTrue("未找到资产文件,跳过", restore != null)
        val io = Anime4kShader.assignTargets(Anime4kShader.plan(listOf(restore!!.readText()), 1920, 1080))
        assertEquals(4, io.size)
        val last = io.last()
        assertEquals(Anime4kShader.SCREEN_TARGET, last.outputTarget)
        assertEquals(Anime4kShader.INPUT_BINDING, last.bindings.first { it.name == "MAIN" }.target)
    }

    @Test
    fun plan_keeps_one_to_one_tier_without_present_pass() {
        val restore = asset("Anime4K_Restore_CNN_S.glsl")
        assumeTrue("未找到资产文件,跳过", restore != null)
        val planned = Anime4kShader.plan(listOf(restore!!.readText()), 1920, 1080)
        assertEquals(4, planned.size)
        assertTrue(planned.last().writesScreen)
        assertTrue(planned.none { it.pass.name == "present" })
    }

    @Test
    fun deblur_apply_reads_the_image_not_the_kernel() {
        val deblur = passesOf("Anime4K_Deblur_DoG.glsl")
        assumeTrue("未找到资产文件,跳过", deblur != null)
        val io = Anime4kShader.assignTargets(Anime4kShader.planPasses(deblur!!, 640, 360, null))
        assertEquals(4, io.size)
        val apply = io[3]
        assertEquals("MAIN", apply.planned.pass.save)
        val hooked = apply.bindings.first { it.name == Anime4kShader.HOOKED }
        val luma = apply.bindings.first { it.name == "LINELUMA" }
        val kernel = apply.bindings.first { it.name == "MMKERNEL" }
        assertEquals(Anime4kShader.INPUT_BINDING, hooked.target)
        assertTrue("LINELUMA 是辅助纹理,不能顶替图像", luma.target != hooked.target)
        assertTrue("MMKERNEL 是辅助纹理,不能顶替图像", kernel.target != hooked.target)
        assertEquals(io[1].outputTarget, io[2].bindings.first { it.name == "MMKERNEL" }.target)
        assertTrue(io[2].outputTarget != io[1].outputTarget)
        assertEquals(io[2].outputTarget, kernel.target)
    }

    @Test
    fun deblur_passes_inherit_the_current_image_size() {
        val restore = passesOf("Anime4K_Restore_CNN_S.glsl")
        val upscale = passesOf("Anime4K_Upscale_CNN_x2_S.glsl")
        val deblur = passesOf("Anime4K_Deblur_DoG.glsl")
        assumeTrue("未找到资产文件,跳过", restore != null && upscale != null && deblur != null)
        val planned = Anime4kShader.planPasses(restore!! + upscale!! + deblur!!, 1280, 720, null)
        val luma = planned.first { it.pass.name.contains("Deblur-DoG-(HQ)-Luma") }
        assertEquals(2560, luma.width)
        assertEquals(1440, luma.height)
        assertTrue(
            "四步去模糊都要跟着当前图像走",
            planned.filter { it.pass.name.contains("Deblur") }.all { it.width == 2560 && it.height == 1440 },
        )
    }

    @Test
    fun every_tier_asset_resolves_under_the_runtime_asset_dir() {
        val root = listOf(File("src/main/assets"), File("app/src/main/assets"))
            .firstOrNull { it.isDirectory }
        assumeTrue("未找到 assets 目录,跳过", root != null)
        Anime4kTier.entries.forEach { tier ->
            tier.assets.forEach { name ->
                assertTrue(
                    "${tier.name} 运行时读不到 $name",
                    File(root, ANIME4K_ASSET_DIR + name).isFile,
                )
            }
        }
    }
}
