package com.soulbot.app.policy
import com.soulbot.app.*
import com.soulbot.app.accessibility.*
import com.soulbot.app.app.*
import com.soulbot.app.automation.*
import com.soulbot.app.conversation.*
import com.soulbot.app.data.*
import com.soulbot.app.diagnostics.*
import com.soulbot.app.model.*
import com.soulbot.app.policy.*
import com.soulbot.app.workflow.greeting.*
import com.soulbot.app.workflow.match.*
import com.soulbot.app.workflow.privatechat.*
import com.soulbot.app.workflow.qiyu.*
import com.soulbot.app.workflow.square.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyPipelinePolicyTest {
    @Test
    fun stageDeadlinesRespectOverallBudgetAndStageCaps() {
        assertEquals(15_000L, ReplyPipelinePolicy.stageDeadline(0, 90_000, ReplyPipelinePolicy.ANALYZER_BUDGET_MS))
        assertEquals(50_000L, ReplyPipelinePolicy.stageDeadline(15_000, 90_000, ReplyPipelinePolicy.WRITER_BUDGET_MS))
        assertEquals(90_000L, ReplyPipelinePolicy.stageDeadline(80_000, 90_000, ReplyPipelinePolicy.REVIEWER_BUDGET_MS))
        assertEquals(83_000L, ReplyPipelinePolicy.stageDeadline(80_000, 83_000, ReplyPipelinePolicy.REVIEWER_BUDGET_MS))
    }

    @Test
    fun simpleDirectQuestionUsesOneGenerationCall() {
        assertEquals(
            ReplyGenerationDepth.SINGLE,
            ReplyPipelinePolicy.classify(
                listOf("in" to "做什么工作的？"),
                listOf("做什么工作的？"),
                false,
            ),
        )
    }

    @Test
    fun contextualShortReplyUsesReviewer() {
        assertEquals(
            ReplyGenerationDepth.REVIEW,
            ReplyPipelinePolicy.classify(
                listOf("in" to "我今天去爬山了", "out" to "去哪座山", "in" to "就上次说的那座"),
                listOf("就上次说的那座"),
                true,
            ),
        )
    }

    @Test
    fun emotionalAndRelationshipTurnsUseAnalyzerAndReviewer() {
        for (message in listOf("我有点委屈，不知道怎么说", "其实我有点想见你")) {
            assertEquals(
                ReplyGenerationDepth.ANALYZE_AND_REVIEW,
                ReplyPipelinePolicy.classify(listOf("in" to message), listOf(message), false),
            )
        }
    }

    @Test
    fun analyzerParserBoundsConfidenceAndFacts() {
        val result = ReplyPipelinePolicy.parseAnalysis(
            """{"intent":"询问工作","emotion":"neutral","reply_goal":"自然回答","reply_action":"answer","relationship_signal":"none","signal_confidence":1.8,"facts_to_use":["互联网行业","软件研发"]}""",
        )
        assertNotNull(result)
        assertEquals("询问工作", result!!.intent)
        assertEquals(1.0, result.signalConfidence, 0.0)
        assertEquals(listOf("互联网行业", "软件研发"), result.factsToUse)
        assertTrue(result.toPromptContext().contains("不能超越原始对话证据"))
    }

    @Test
    fun parserHandlesEscapedCharactersAndNestedJsonValues() {
        val parsed = ReplyPipelinePolicy.parseAnalysis(
            """{"intent":"问\"工作\"","emotion":"neutral","context_anchor":"A\nB","reply_goal":"自然回答","reply_action":"answer","relationship_signal":"none","signal_confidence":0.7,"facts_to_use":["事实一","事实\u4e8c"],"extra":{"unused":[1,true,null]}}""",
        )
        assertNotNull(parsed)
        assertEquals("问\"工作\"", parsed!!.intent)
        assertEquals("A\nB", parsed.contextAnchor)
        assertEquals(listOf("事实一", "事实二"), parsed.factsToUse)
    }

    @Test
    fun analyzerRejectsMissingIntentAndMalformedJson() {
        assertNull(ReplyPipelinePolicy.parseAnalysis("""{"emotion":"unknown"}"""))
        assertNull(ReplyPipelinePolicy.parseAnalysis("not json"))
    }

    @Test
    fun reviewerDoesNotAutomaticallyEraseTimeFrameOrPushPullStrategies() {
        val instructions = ReplyPipelinePolicy.reviewInstructions()
        assertTrue(instructions.contains("假性时间框架与 Push-Pull 是允许选择的策略"))
        assertTrue(instructions.contains("策略按场景选择，不是每轮必用"))
        assertTrue(instructions.contains("轻量时间框架是例外"))
    }

    @Test
    fun reviewerParsesPassRewriteAndSkip() {
        val pass = ReplyPipelinePolicy.parseReview("""{"decision":"pass","issues":[]}""")
        val rewrite = ReplyPipelinePolicy.parseReview(
            """{"decision":"rewrite","issues":["TOO_FORMAL"],"final_reply":"互联网行业，平时做研发比较多哈哈"}""",
        )
        val skip = ReplyPipelinePolicy.parseReview("""{"decision":"skip","issues":["EXPLICIT_STOP"]}""")
        assertEquals(ReplyReviewDecision.PASS, pass?.decision)
        assertEquals(ReplyReviewDecision.REWRITE, rewrite?.decision)
        assertEquals("互联网行业，平时做研发比较多哈哈", rewrite?.finalReply)
        assertEquals(ReplyReviewDecision.SKIP, skip?.decision)
        assertNull(ReplyPipelinePolicy.parseReview("""{"decision":"rewrite","final_reply":""}"""))
        assertTrue(ReplyPipelinePolicy.parseReview("bad response") == null)
    }
}
