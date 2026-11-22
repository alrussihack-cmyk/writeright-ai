package com.writeright.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.writeright.ai.R
import com.writeright.ai.board.WordIssue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplainSheet(
    issue: WordIssue,
    onSpeak: () -> Unit,
    onRewrite: () -> Unit,
    onMarkCorrect: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Brand.Cream,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "الغلطة",
                style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Black, color = Brand.Ink),
            )

            // written -> correct
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WordChip(issue.written, Brand.CoralSoft, strike = true)
                    Text("→", style = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Black, color = Brand.Ink))
                    WordChip(issue.suggestion, Brand.Mint, strike = false)
                }
            }

            // what exactly is wrong
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brand.Paper, RoundedCornerShape(18.dp))
                    .border(3.dp, Brand.Ink, RoundedCornerShape(18.dp))
                    .padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = issue.info.title,
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Brand.Ink),
                    textAlign = TextAlign.Center,
                )
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        issue.suggestion.forEachIndexed { index, ch ->
                            LetterBox(ch.toString(), marked = index in issue.info.marks)
                        }
                    }
                }
            }

            // tip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brand.YellowSoft, RoundedCornerShape(18.dp))
                    .border(3.dp, Brand.Ink, RoundedCornerShape(18.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "نصيحة:",
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Brand.Ink),
                )
                Text(
                    text = issue.info.tip,
                    style = TextStyle(fontSize = 14.sp, lineHeight = 22.sp, color = Brand.Ink),
                    modifier = Modifier.weight(1f),
                )
            }

            // actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .brandBox(Brand.Paper, 16.dp)
                        .clickable(role = Role.Button, onClick = onSpeak),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_speaker),
                        contentDescription = "اسمع الكلمة",
                        tint = Brand.Ink,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(58.dp)
                        .brandBox(Brand.Ink, 16.dp, shadow = 4.dp)
                        .clickable(role = Role.Button, onClick = onRewrite),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "اكتبها تاني على اللوحة",
                        style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Black, color = Brand.Mint),
                    )
                }
            }

            TextButton(
                onClick = onMarkCorrect,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(
                    text = "أنا كاتبها صح (القراءة غلطت)",
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Brand.Ink),
                )
            }
        }
    }
}

@Composable
private fun WordChip(text: String, bg: androidx.compose.ui.graphics.Color, strike: Boolean) {
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(14.dp))
            .border(3.dp, Brand.Ink, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = Brand.Ink,
                textDecoration = if (strike) TextDecoration.LineThrough else TextDecoration.None,
            ),
        )
    }
}

@Composable
private fun LetterBox(letter: String, marked: Boolean) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .size(width = 36.dp, height = 44.dp)
            .background(if (marked) Brand.Mint else Brand.MintSoft, shape)
            .border(3.dp, Brand.Ink, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letter,
            style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Brand.Ink),
        )
    }
}
