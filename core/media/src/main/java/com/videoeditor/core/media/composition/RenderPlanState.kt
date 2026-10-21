package com.videoeditor.core.media.composition

import com.videoeditor.core.model.render.ProjectRenderPlan

/** One adapter session owns this publication. Export never shares preview state or GL objects. */
class RenderPlanState(initial: ProjectRenderPlan) {
    @Volatile var plan: ProjectRenderPlan = initial
        private set
    fun update(value: ProjectRenderPlan) {
        require(plan.hasSameTopology(value)) { "A source topology change requires a new render session" }
        plan = value
    }
}
