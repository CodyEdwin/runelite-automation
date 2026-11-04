/*
 * Copyright (c) 2026, reich
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
/**
 * The RSBuddy routine framework.
 *
 * <h2>Layers</h2>
 * <pre>
 *   Routine            base class: name, status, tick + per-frame hooks
 *     TaskRoutine      a priority list of tasks: "when this holds, do that"
 *       your tasks     call actions.* only
 *
 *   Actions            thin facade over the action modules
 *     Aim              dead-centre target, drift correction, drop target, reaction delay
 *     Interact         verified real clicks (objects, NPCs, widgets, items)
 *     Movement         minimap walking, isMoving, click-or-walk
 *     Bank / Keys / Waits / ActionLog
 *       GameInput      curved human mouse paths, clicks, keys (refuses direct menu actions)
 *         HardwareInput  OS backend: X11 XTest or Windows SendInput (no Java input fallback)
 * </pre>
 *
 * <h2>Lifecycle</h2>
 * {@link net.runelite.rsbuddy.routine.RoutineManager} starts one routine at a time. It refuses
 * to start without a hardware input backend, locks GameInput to physical clicks, then calls
 * {@code onStart}, {@code onGameTick} every game tick and {@code onClientTick} every frame.
 *
 * <h2>Settings</h2>
 * A routine declares {@link net.runelite.rsbuddy.routine.RoutineSetting}s in
 * {@code getRoutineSettings()}. They are drawn in its settings form in the side panel and stored
 * in the RSBuddy config group under the same key; {@code TaskRoutine.bool/integer/text} read them.
 */
package net.runelite.rsbuddy.routine;
