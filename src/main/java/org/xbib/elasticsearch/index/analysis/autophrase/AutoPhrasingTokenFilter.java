/*
 * Copyright (C) 2016 Jörg Prante
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program; if not, see http://www.gnu.org/licenses
 * or write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * The interactive user interfaces in modified source and object code
 * versions of this program must display Appropriate Legal Notices,
 * as required under Section 5 of the GNU Affero General Public License.
 *
 */
package org.xbib.elasticsearch.index.analysis.autophrase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.lucene.util.AttributeImpl;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.util.CharArraySet;
import org.apache.lucene.analysis.util.CharArrayMap;

/**
 * Performs "auto phrasing" on a token stream. Auto phrases refer to sequences of tokens that
 * are meant to describe a single thing and should be searched for as such. When these phrases
 * are detected in the token stream, a single token representing the phrase is emitted rather than
 * the individual tokens that make up the phrase. The filter supports overlapping phrases.
 *
 * The Autophrasing filter can be combined with a synonym filter to handle cases in which prefix or
 * suffix terms in a phrase are synonymous with the phrase, but where other parts of the phrase are
 * not.
 */
public class AutoPhrasingTokenFilter extends TokenFilter {

    private CharArrayMap<CharArraySet> phraseMap;

    private CharArraySet currentSetToCheck;

    private StringBuilder currentPhrase;

    private List<Token> unusedTokens;

    private boolean emitSingleTokens;

    private char[] lastToken;
    private char[] lastEmitted;
    private char[] lastValid;

    private Character replaceWhitespaceWith;

    private int positionIncr;

    public AutoPhrasingTokenFilter(TokenStream input, CharArraySet phraseSet, boolean emitSingleTokens) {
        super(input);
        this.emitSingleTokens = emitSingleTokens;
        this.phraseMap = convertPhraseSet(phraseSet);
        this.currentPhrase = new StringBuilder();
        this.unusedTokens = new ArrayList<>();
        this.positionIncr = 0;
    }

    public void setReplaceWhitespaceWith(Character replaceWhitespaceWith) {
        this.replaceWhitespaceWith = replaceWhitespaceWith;
    }

    @Override
    public void reset() throws IOException {
        currentSetToCheck = null;
        currentPhrase.setLength(0);
        lastToken = null;
        lastEmitted = null;
        unusedTokens.clear();
        positionIncr = 0;
        super.reset();
    }

    @Override
    public final boolean incrementToken() throws IOException {
        if (!emitSingleTokens && unusedTokens.size() > 0) {
            Token aToken = unusedTokens.remove(0);
            emit(aToken);
            return true;
        }
        if (lastToken != null) {
            emit(lastToken);
            lastToken = null;
            return true;
        }
        char[] nextToken = nextToken();
        if (nextToken == null) {
            if (lastValid != null) {
                emit(lastValid);
                lastValid = null;
                return true;
            }
            if (emitSingleTokens && currentSetToCheck != null && currentSetToCheck.size() > 0) {
                char[] phrase = getFirst(currentSetToCheck);
                char[] lastTok = getCurrentBuffer(new char[0]);
                if (phrase != null && endsWith(lastTok, phrase)) {
                    currentSetToCheck = remove(phrase);
                    emit(phrase);
                    return true;
                }
            }
            else if (!emitSingleTokens && currentSetToCheck != null && currentSetToCheck.size() > 0) {
                char[] currBuff = getCurrentBuffer(new char[0] );
                if (lastEmitted != null && !equals(fixWhitespace(lastEmitted), currBuff)) {
                    discardCharTokens(currentPhrase, unusedTokens);
                    currentSetToCheck = null;
                    if (unusedTokens.size() > 0) {
                        Token aToken = unusedTokens.remove(0);
                        if (!endsWith(lastEmitted, currBuff)) {
                            emit(aToken);
                            return true;
                        }
                    }
                }
            }
            if (lastEmitted == null && (currentPhrase != null && currentPhrase.length() > 0)) {
                char[] lastTok = getCurrentBuffer(new char[0]);
                if (currentSetToCheck.contains(lastTok, 0, lastTok.length )) {
                    emit(lastTok);
                    currentPhrase.setLength(0);
                    return true;
                }
                else if (!emitSingleTokens) {
                    discardCharTokens(currentPhrase, unusedTokens);
                    currentSetToCheck = null;
                    currentPhrase.setLength(0);
                    if (unusedTokens.size() > 0) {
                        Token aToken = unusedTokens.remove(0);
                        emit(aToken);
                        return true;
                    }
                }
            }
            return false;
        }
        if (emitSingleTokens) {
            lastToken = nextToken;
        }
        if (currentSetToCheck == null || currentSetToCheck.size() == 0 ) {
            if (phraseMap.keySet().contains(nextToken, 0, nextToken.length )) {
                currentSetToCheck = phraseMap.get(nextToken, 0, nextToken.length );
                if (currentPhrase == null) {
                    currentPhrase = new StringBuilder();
                } else {
                    currentPhrase.setLength(0);
                }
                currentPhrase.append(nextToken);
                return incrementToken();
            }
            else {
                emit(nextToken);
                lastToken = null;
                return true;
            }
        }
        else {
            char[] currentBuffer = getCurrentBuffer(nextToken);
            if (currentSetToCheck.contains(currentBuffer, 0, currentBuffer.length)) {
                currentSetToCheck = remove(currentBuffer);
                if (currentSetToCheck.size() == 0) {
                    emit(currentBuffer);
                    lastValid = null;
                    --positionIncr;
                }
                else {
                    if (emitSingleTokens) {
                        lastToken = currentBuffer;
                        return true;
                    }
                    lastValid = currentBuffer;
                }
                if (phraseMap.keySet().contains(nextToken, 0, nextToken.length)) {
                    currentSetToCheck = phraseMap.get(nextToken, 0, nextToken.length );
                    if (currentPhrase == null) {
                        currentPhrase = new StringBuilder();
                    } else {
                        currentPhrase.setLength(0);
                    }
                    currentPhrase.append(nextToken);
                }
                return lastValid == null || incrementToken();
            }
            if (phraseMap.keySet().contains(nextToken, 0, nextToken.length)) {
                CharArraySet newSet = phraseMap.get(nextToken, 0, nextToken.length);
                for (Object aNewSet : newSet) {
                    char[] phrase = (char[]) aNewSet;
                    currentSetToCheck.add(phrase);
                }
            }
            for (Object aCurrentSetToCheck : currentSetToCheck) {
                char[] phrase = (char[]) aCurrentSetToCheck;
                if (startsWith(phrase, currentBuffer)) {
                    return incrementToken();
                }
            }
            if (lastValid != null) {
                emit(lastValid);
                lastValid = null;
                return true;
            }
            if (!emitSingleTokens) {
                discardCharTokens(currentPhrase, unusedTokens);
                currentPhrase.setLength(0);
                currentSetToCheck = null;

                if (unusedTokens.size() > 0) {
                    Token aToken = unusedTokens.remove(0);
                    emit(aToken);
                    return true;
                }
            }
            currentSetToCheck = null;
            return incrementToken();
        }
    }

    private char[] nextToken() throws IOException {
        if (input.incrementToken()) {
            CharTermAttribute termAttr = getTermAttribute();
            if (termAttr != null) {
                char[] termBuf = termAttr.buffer();
                char[] nextTok = new char[termAttr.length()];
                System.arraycopy(termBuf, 0, nextTok, 0, termAttr.length());
                return nextTok;
            }
        }
        return null;
    }

    private boolean startsWith(char[] buffer, char[] phrase) {
        if (phrase.length > buffer.length) return false;
        for (int i = 0; i < phrase.length; i++){
            if (buffer[i] != phrase[i]) return false;
        }
        return true;
    }

    private boolean equals(char[] buffer, char[] phrase) {
        if (phrase.length != buffer.length) return false;
        for (int i = 0; i < phrase.length; i++){
            if (buffer[i] != phrase[i]) return false;
        }
        return true;
    }

    private boolean endsWith(char[] buffer, char[] phrase) {
        if (buffer == null || phrase == null) return false;

        if (phrase.length >= buffer.length) return false;
        for (int i = 1; i < phrase.length-1; ++i){
            if (buffer[buffer.length - i] != phrase[phrase.length - i]) return false;
        }
        return true;
    }

    private char[] getCurrentBuffer(char[] newToken) {
        if (currentPhrase == null) {
            currentPhrase = new StringBuilder();
        }
        if (newToken != null && newToken.length > 0) {
            if (currentPhrase.length() > 0) currentPhrase.append(' ');
            currentPhrase.append(newToken);
        }
        char[] currentBuff = new char[ currentPhrase.length() ];
        currentPhrase.getChars(0, currentPhrase.length(), currentBuff, 0);
        return currentBuff;
    }

    private char[] getFirst(CharArraySet charSet) {
        if (charSet.isEmpty()) {
            return null;
        }
        Iterator<Object> phraseIt = charSet.iterator();
        return (char[])phraseIt.next();
    }

    private void emit(char[] token) {
        if (replaceWhitespaceWith != null) {
            token = replaceWhiteSpace(token);
        }
        CharTermAttribute termAttr = getTermAttribute();
        assert termAttr != null;
        termAttr.setEmpty();
        termAttr.append(new StringBuilder().append(token));
        OffsetAttribute offAttr = getOffsetAttribute();
        if (offAttr != null && offAttr.endOffset() >= token.length){
            int start = offAttr.endOffset() - token.length;
            offAttr.setOffset(start, offAttr.endOffset());
        }
        PositionIncrementAttribute pia = getPositionIncrementAttribute();
        if (pia != null) {
            pia.setPositionIncrement(++positionIncr);
        }
        lastEmitted = token;
    }

    private void emit(Token token) {
        emit(token.tok);
        OffsetAttribute offAttr = getOffsetAttribute();
        if (token.endPos > token.startPos && token.startPos >= 0) {
            assert offAttr != null;
            offAttr.setOffset(token.startPos, token.endPos);
        }
    }

    private char[] replaceWhiteSpace(char[] token) {
        char[] replaced = new char[ token.length ];
        for (int i = 0; i < token.length; i++ ) {
            if (token[i] == ' ' ) {
                replaced[i] = replaceWhitespaceWith;
            }
            else {
                replaced[i] = token[i];
            }
        }
        return replaced;
    }

    private CharTermAttribute getTermAttribute() {
        Iterator<AttributeImpl> attrIt = getAttributeImplsIterator();
        while (attrIt != null && attrIt.hasNext() ) {
            AttributeImpl attrImp = attrIt.next();
            if (attrImp instanceof CharTermAttribute) {
                return (CharTermAttribute)attrImp;
            }
        }
        return null;
    }

    private OffsetAttribute getOffsetAttribute() {
        Iterator<AttributeImpl> attrIt = getAttributeImplsIterator();
        while (attrIt != null && attrIt.hasNext() ) {
            AttributeImpl attrImp = attrIt.next();
            if (attrImp instanceof OffsetAttribute) {
                return (OffsetAttribute)attrImp;
            }
        }
        return null;
    }

    private PositionIncrementAttribute getPositionIncrementAttribute() {
        Iterator<AttributeImpl> attrIt = getAttributeImplsIterator();
        while (attrIt != null && attrIt.hasNext() ) {
            AttributeImpl attrImp = attrIt.next();
            if (attrImp instanceof PositionIncrementAttribute) {
                return (PositionIncrementAttribute)attrImp;
            }
        }
        return null;
    }

    private CharArrayMap<CharArraySet> convertPhraseSet(CharArraySet phraseSet ) {
        CharArrayMap<CharArraySet> phraseMap = new CharArrayMap<>(100, false);
        for (Object aPhraseSet : phraseSet) {
            char[] phrase = (char[]) aPhraseSet;
            char[] firstTerm = getFirstTerm(phrase);
            CharArraySet itsPhrases = phraseMap.get(firstTerm, 0, firstTerm.length);
            if (itsPhrases == null) {
                itsPhrases = new CharArraySet(5, false);
                phraseMap.put(new String(firstTerm), itsPhrases);
            }
            itsPhrases.add(phrase);
        }
        return phraseMap;
    }

    private char[] getFirstTerm(char[] phrase) {
        int spNdx = 0;
        while (spNdx < phrase.length) {
            if (isSpaceChar(phrase[spNdx++])) {
                break;
            }
        }
        char[] firstCh = new char[ spNdx-1 ];
        System.arraycopy(phrase, 0, firstCh, 0, spNdx-1);
        return firstCh;
    }

    private boolean isSpaceChar(char ch ) {
        return " \t\n\r".indexOf(ch) >= 0;
    }

    private void discardCharTokens(StringBuilder phrase, List<Token> tokenList) {
        OffsetAttribute offAttr = getOffsetAttribute();
        assert offAttr != null;
        int endPos = offAttr.endOffset();
        int startPos = endPos - phrase.length();
        int lastSp = 0;
        for (int i = 0; i < phrase.length(); i++ ) {
            char chAt = phrase.charAt(i);
            if (isSpaceChar(chAt ) && i > lastSp) {
                char[] tok = new char[ i - lastSp ];
                phrase.getChars(lastSp, i, tok, 0 );
                if (lastEmitted == null || !endsWith(lastEmitted, tok)) {
                    Token token = new Token();
                    token.tok = tok;
                    token.startPos = startPos + lastSp;
                    token.endPos = token.startPos + tok.length;
                    tokenList.add(token);
                }
                lastSp = i+1;
            }
        }
        char[] tok = new char[phrase.length() - lastSp];
        phrase.getChars(lastSp, phrase.length(), tok, 0);
        Token token = new Token();
        token.tok = tok;
        token.endPos = endPos;
        token.startPos = endPos - tok.length;
        tokenList.add(token);
    }

    private CharArraySet remove(char[] charArray) {
        CharArraySet newSet = new CharArraySet(5, false);
        for (Object aCurrentSetToCheck : currentSetToCheck) {
            char[] phrase = (char[]) aCurrentSetToCheck;
            if (!equals(phrase, charArray) && startsWith(phrase, charArray) || endsWith(charArray, phrase)) {
                newSet.add(phrase);
            }
        }
        return newSet;
    }

    private char[] fixWhitespace(char[] phrase) {
        if (replaceWhitespaceWith == null) return phrase;
        char[] fixed = new char[ phrase.length ];
        for (int i = 0; i < phrase.length; i++) {
            if (phrase[i] == replaceWhitespaceWith) {
                fixed[i] = ' ';
            }
            else {
                fixed[i] = phrase[i];
            }
        }
        return fixed;
    }

    private class Token {
        char[] tok;
        int startPos;
        int endPos;
    }
}