package com.codespace.ide.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// ─────────────────────────────────────────────────────────────────────────────
// OnboardingWalkthrough — shown once on first launch after login
// Usage: if (showOnboarding) OnboardingWalkthrough(onDone = { showOnboarding = false })
// ─────────────────────────────────────────────────────────────────────────────

data class OnboardingStep(
    val icon: ImageVector,
    val iconTint: Color,
    val title: String,
    val description: String,
    val tip: String? = null,
    val bullets: List<String> = emptyList(),
)

private val steps = listOf(
    OnboardingStep(
        icon = Icons.Default.Description,
        iconTint = Color(0xFF007ACC),
        title = "Explorer",
        description = "Browse your project files and folders. Tap any file to open it in the editor. Long-press a file for rename, delete, and copy options.",
        tip = "Tap the file icon in the left sidebar to toggle the Explorer panel."
    ),
    OnboardingStep(
        icon = Icons.Default.Edit,
        iconTint = Color(0xFF4EC9B0),
        title = "Code Editor",
        description = "A full-featured code editor with syntax highlighting, line numbers, and find & replace. Supports all major languages.",
        tip = "Pinch to zoom the font size. Use the ⋮ menu in the editor tab for split view."
    ),
    OnboardingStep(
        icon = Icons.Default.Terminal,
        iconTint = Color(0xFF4CAF50),
        title = "Terminal",
        description = "A full Linux terminal powered by Ubuntu. Run commands, install packages, and manage your environment — all from your phone.",
        tip = "Tap ⌨ in the terminal menu to show the full keyboard bar with F1–F12, Ctrl combos, and symbols."
    ),
    OnboardingStep(
        icon = Icons.Default.Chat,
        iconTint = Color(0xFFBD93F9),
        title = "AI Assistant",
        description = "Ask the AI to explain code, generate functions, fix bugs, or write docs. It's context-aware — it knows what file you have open.",
        tip = "Tap the chat bubble icon in the top-right toolbar to open the AI panel."
    ),
    OnboardingStep(
        icon = Icons.Default.AccountTree,
        iconTint = Color(0xFFF1FA8C),
        title = "Source Control",
        description = "Stage, commit, push, and pull from your Git repository without leaving the app. View diffs and manage branches.",
        tip = "Tap the branch icon in the left sidebar to open Source Control."
    ),
    OnboardingStep(
        icon = Icons.Default.BugReport,
        iconTint = Color(0xFFFFB86C),
        title = "Run & Debug",
        description = "Queue run requests and view debug output. Use the output panel at the bottom to monitor logs and errors in real time.",
        tip = "Tap the ▶ icon in the left sidebar to access Run & Debug."
    ),
    OnboardingStep(
        icon = Icons.Default.Extension,
        iconTint = Color(0xFFFF79C6),
        title = "Extensions",
        description = "Install language servers, themes, MCP tools, and shell extensions to supercharge your workflow.",
        tip = "Tap the puzzle piece icon at the bottom of the left sidebar."
    ),
    OnboardingStep(
        icon = Icons.Default.Settings,
        iconTint = Color(0xFF969696),
        title = "Settings & Themes",
        description = "Change color themes, terminal themes, set up your shell profile, install offline essentials, and configure keyboard shortcuts.",
        tip = "Tap the ⚙ gear icon at the bottom of the sidebar to open the settings menu."
    ),
    OnboardingStep(
        icon = Icons.Default.Visibility,
        iconTint = Color(0xFFFF79C6),
        title = "Preview Panel",
        description = "Live preview for 4 file types — no browser switching needed.",
        bullets = listOf(
            "HTML  →  full render with JS & CSS",
            "Markdown  →  styled dark-mode render",
            "SVG  →  centered vector preview",
            "Browser  →  embedded browser, point it at localhost:3000",
        ),
        tip = "Open any .html / .md / .svg file then tap PREVIEW in the bottom panel."
    ),
)

@Composable
fun OnboardingWalkthrough(onDone: () -> Unit) {
    var currentStep by remember { mutableStateOf(0) }
    val step = steps[currentStep]
    val isLast = currentStep == steps.lastIndex

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xCC000000)),
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                targetState = currentStep,
                transitionSpec = {
                    if (targetState > initialState) {
                        slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it } + fadeOut()
                    } else {
                        slideInHorizontally { -it } + fadeIn() togetherWith slideOutHorizontally { it } + fadeOut()
                    }
                },
                label = "onboarding_step"
            ) { stepIdx ->
                val s = steps[stepIdx]
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.88f)
                        .wrapContentHeight(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                    elevation = CardDefaults.cardElevation(24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Step counter dots
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(bottom = 24.dp)
                        ) {
                            steps.forEachIndexed { idx, _ ->
                                Box(
                                    modifier = Modifier
                                        .size(if (idx == stepIdx) 10.dp else 6.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (idx == stepIdx) Color(0xFF007ACC)
                                            else if (idx < stepIdx) Color(0xFF4EC9B0)
                                            else Color(0xFF444444)
                                        )
                                )
                            }
                        }

                        // Icon
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(s.iconTint.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = s.icon,
                                contentDescription = null,
                                tint = s.iconTint,
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        Spacer(Modifier.height(20.dp))

                        // Step label
                        Text(
                            text = "${stepIdx + 1} of ${steps.size}",
                            fontSize = 11.sp,
                            color = Color(0xFF717171),
                            letterSpacing = 1.sp
                        )

                        Spacer(Modifier.height(6.dp))

                        // Title
                        Text(
                            text = s.title,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFD4D4D4),
                            textAlign = TextAlign.Center
                        )

                        Spacer(Modifier.height(12.dp))

                        // Description
                        Text(
                            text = s.description,
                            fontSize = 14.sp,
                            color = Color(0xFF9CDCFE),
                            textAlign = TextAlign.Center,
                            lineHeight = 22.sp
                        )

                        // Bullet list (optional)
                        if (s.bullets.isNotEmpty()) {
                            Spacer(Modifier.height(14.dp))
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF252526))
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                s.bullets.forEach { bullet ->
                                    Row(
                                        verticalAlignment = Alignment.Top,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text("▸", fontSize = 11.sp, color = s.iconTint, modifier = Modifier.padding(top = 1.dp))
                                        Text(bullet, fontSize = 12.sp, color = Color(0xFFCCCCCC), lineHeight = 18.sp)
                                    }
                                }
                            }
                        }

                        // Tip chip
                        if (s.tip != null) {
                            Spacer(Modifier.height(16.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF252526))
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.Lightbulb, null, tint = Color(0xFFF1FA8C), modifier = Modifier.size(16.dp).padding(top = 1.dp))
                                Text(s.tip, fontSize = 12.sp, color = Color(0xFFCCCCCC), lineHeight = 18.sp)
                            }
                        }

                        Spacer(Modifier.height(28.dp))

                        // Navigation buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Skip / Back
                            if (stepIdx == 0) {
                                TextButton(onClick = onDone) {
                                    Text("Skip", color = Color(0xFF717171), fontSize = 13.sp)
                                }
                            } else {
                                TextButton(onClick = { currentStep-- }) {
                                    Icon(Icons.Default.ArrowBack, null, tint = Color(0xFF717171), modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Back", color = Color(0xFF717171), fontSize = 13.sp)
                                }
                            }

                            // Next / Done
                            Button(
                                onClick = { if (isLast) onDone() else currentStep++ },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007ACC)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    if (isLast) "Get Started" else "Next",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (!isLast) {
                                    Spacer(Modifier.width(4.dp))
                                    Icon(Icons.Default.ArrowForward, null, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
