import React, { useMemo } from 'react';
import { ScrollView, Text, View, StyleSheet } from 'react-native';
import { Colors, FontSizes } from '../theme/colors';

// Simple tokenizer for common languages
function tokenize(code, language) {
  const lines = code.split('\n');
  return lines.map((line) => tokenizeLine(line, language));
}

function tokenizeLine(line, language) {
  const tokens = [];
  let remaining = line;

  // Comment detection
  if (language === 'python') {
    const commentIdx = remaining.indexOf('#');
    if (commentIdx !== -1) {
      tokens.push({ text: remaining.slice(0, commentIdx), type: 'code' });
      tokens.push({ text: remaining.slice(commentIdx), type: 'comment' });
      return tokens;
    }
  }
  if (['javascript','typescript','java','cpp','c','csharp','go','rust','swift','kotlin'].includes(language)) {
    const singleComment = remaining.indexOf('//');
    if (singleComment !== -1) {
      const beforeComment = remaining.slice(0, singleComment);
      tokens.push(...tokenizeInline(beforeComment, language));
      tokens.push({ text: remaining.slice(singleComment), type: 'comment' });
      return tokens;
    }
  }

  return tokenizeInline(remaining, language);
}

function tokenizeInline(text, language) {
  const tokens = [];
  let remaining = text;

  const KEYWORDS = {
    javascript: ['const','let','var','function','return','if','else','for','while','class','import','export','default','from','async','await','try','catch','new','this','typeof','instanceof','true','false','null','undefined','switch','case','break','continue','throw','extends','super'],
    typescript: ['const','let','var','function','return','if','else','for','while','class','import','export','default','from','async','await','try','catch','new','this','typeof','instanceof','true','false','null','undefined','switch','case','break','continue','throw','extends','interface','type','enum','implements','abstract','readonly','public','private','protected','string','number','boolean','void','any'],
    python: ['def','class','return','import','from','if','elif','else','for','while','in','not','and','or','True','False','None','try','except','finally','with','as','pass','break','continue','raise','yield','lambda','global','nonlocal','del','assert'],
    java: ['public','private','protected','static','void','class','interface','extends','implements','new','return','if','else','for','while','try','catch','finally','import','package','final','abstract','this','super','true','false','null','int','String','boolean','long','double','float'],
    go: ['func','var','const','type','struct','interface','return','if','else','for','range','import','package','switch','case','default','break','continue','go','chan','select','defer','map','slice','true','false','nil'],
    rust: ['fn','let','mut','const','struct','enum','impl','trait','return','if','else','for','while','loop','match','use','mod','pub','crate','super','self','true','false'],
    python3: ['def','class','return','import','from','if','elif','else','for','while','in','not','and','or','True','False','None'],
  };

  const keywords = KEYWORDS[language] || KEYWORDS.javascript;

  while (remaining.length > 0) {
    // String literals
    const strMatch = remaining.match(/^("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'|`(?:[^`\\]|\\.)*`)/);
    if (strMatch) {
      tokens.push({ text: strMatch[1], type: 'string' });
      remaining = remaining.slice(strMatch[1].length);
      continue;
    }

    // Numbers
    const numMatch = remaining.match(/^(\b\d+\.?\d*\b)/);
    if (numMatch) {
      tokens.push({ text: numMatch[1], type: 'number' });
      remaining = remaining.slice(numMatch[1].length);
      continue;
    }

    // Keywords / identifiers
    const wordMatch = remaining.match(/^([a-zA-Z_$][a-zA-Z0-9_$]*)/);
    if (wordMatch) {
      const word = wordMatch[1];
      const type = keywords.includes(word) ? 'keyword'
        : /^[A-Z]/.test(word) ? 'class'
        : 'identifier';
      tokens.push({ text: word, type });
      remaining = remaining.slice(word.length);
      continue;
    }

    // Single character
    tokens.push({ text: remaining[0], type: 'punctuation' });
    remaining = remaining.slice(1);
  }

  return tokens;
}

const TOKEN_COLORS = {
  keyword:     Colors.syntax_keyword,
  string:      Colors.syntax_string,
  number:      Colors.syntax_number,
  comment:     Colors.syntax_comment,
  function:    Colors.syntax_function,
  identifier:  Colors.text_primary,
  class:       Colors.syntax_type,
  punctuation: Colors.syntax_punctuation,
  code:        Colors.text_primary,
};

export default function SyntaxHighlighter({ code, language = 'plaintext', fontSize = 13 }) {
  const tokenizedLines = useMemo(() => tokenize(code || '', language), [code, language]);

  return (
    <View style={styles.container}>
      {tokenizedLines.map((lineTokens, lineIdx) => (
        <View key={lineIdx} style={styles.line}>
          <Text style={[styles.lineNum, { fontSize }]}>{lineIdx + 1}</Text>
          <Text style={[styles.codeLine, { fontSize }]}>
            {lineTokens.map((token, i) => (
              <Text key={i} style={{ color: TOKEN_COLORS[token.type] || Colors.text_primary }}>
                {token.text}
              </Text>
            ))}
          </Text>
        </View>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  container: { padding: 4 },
  line: { flexDirection: 'row', minHeight: 20 },
  lineNum: {
    width: 36, textAlign: 'right', marginRight: 8,
    color: Colors.text_secondary, fontFamily: 'monospace', lineHeight: 20,
  },
  codeLine: {
    color: Colors.text_primary, fontFamily: 'monospace',
    lineHeight: 20, flexShrink: 1,
  },
});
