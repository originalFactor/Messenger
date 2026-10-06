/*
 * Copyright 2026 ECSDevs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.ptoe.messenger.renderer

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator

/**
 * Height + fade expand/collapse for a collapsible child view — the native
 * counterpart of Compose's `animateContentSize()` + `AnimatedVisibility`
 * pairing on the tool-call card and think block.
 *
 * The section's LayoutParams.height is animated 0↔measured and reset to
 * WRAP_CONTENT at the ends, so later content changes (streaming updates)
 * relayout naturally once the animation settles. Requires the host to be
 * laid out for the measure pass; before that it falls back to an instant toggle.
 */
internal class SectionAnimator(private val section: View) {

    private var animator: ValueAnimator? = null

    val isRunning: Boolean
        get() = animator?.isRunning == true

    fun animate(expand: Boolean) {
        cancel()
        val host = section.parent as? View
        if (host == null || host.width == 0) {
            applyInstant(expand)
            return
        }
        if (expand) {
            section.visibility = View.VISIBLE
            section.alpha = 0f
            section.measure(
                View.MeasureSpec.makeMeasureSpec(
                    host.width - host.paddingLeft - host.paddingRight,
                    View.MeasureSpec.EXACTLY
                ),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val target = section.measuredHeight
            setHeight(0)
            animator = ValueAnimator.ofInt(0, target).apply {
                duration = 220
                interpolator = DecelerateInterpolator(1.6f)
                addUpdateListener {
                    setHeight(animatedValue as Int)
                    section.alpha = animatedFraction
                }
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        animator = null
                        if (!cancelled) {
                            setHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
                            section.alpha = 1f
                        }
                    }
                })
                start()
            }
        } else {
            val start = if (section.visibility == View.VISIBLE) section.height else 0
            if (start <= 0) {
                applyInstant(false)
                return
            }
            animator = ValueAnimator.ofInt(start, 0).apply {
                duration = 180
                interpolator = AccelerateInterpolator(1.2f)
                addUpdateListener {
                    setHeight(animatedValue as Int)
                    section.alpha = 1f - animatedFraction
                }
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        animator = null
                        if (!cancelled) {
                            section.visibility = View.GONE
                            setHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
                            section.alpha = 1f
                        }
                    }
                })
                start()
            }
        }
    }

    /** Stop any running animation without leaving fixed heights behind. */
    fun cancel() {
        animator?.let {
            animator = null
            it.cancel()
        }
        setHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun applyInstant(expand: Boolean) {
        cancel()
        if (expand) {
            section.visibility = View.VISIBLE
            section.alpha = 1f
            setHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
        } else {
            section.visibility = View.GONE
            section.alpha = 1f
            setHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun setHeight(height: Int) {
        val lp = section.layoutParams ?: ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { section.layoutParams = it }
        if (lp.height != height) {
            lp.height = height
            section.layoutParams = lp
        }
    }
}
