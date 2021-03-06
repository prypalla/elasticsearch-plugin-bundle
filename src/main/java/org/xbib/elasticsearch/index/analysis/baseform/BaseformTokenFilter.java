/*
 * Copyright (C) 2014 Jörg Prante
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
package org.xbib.elasticsearch.index.analysis.baseform;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PackedTokenAttributeImpl;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.util.AttributeSource;
import org.xbib.elasticsearch.common.fsa.Dictionary;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.util.LinkedList;

public class BaseformTokenFilter extends TokenFilter {

    private final LinkedList<PackedTokenAttributeImpl> tokens;

    private final Dictionary dictionary;

    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);

    private final PositionIncrementAttribute posIncAtt = addAttribute(PositionIncrementAttribute.class);

    private AttributeSource.State current;

    protected BaseformTokenFilter(TokenStream input, Dictionary dictionary) {
        super(input);
        this.tokens = new LinkedList<>();
        this.dictionary = dictionary;
    }

    @Override
    public final boolean incrementToken() throws IOException {
        if (!tokens.isEmpty()) {
            assert current != null;
            PackedTokenAttributeImpl token = tokens.removeFirst();
            restoreState(current);
            termAtt.setEmpty().append(token);
            posIncAtt.setPositionIncrement(0);
            return true;
        }
        if (input.incrementToken()) {
            baseform();
            if (!tokens.isEmpty()) {
                current = captureState();
            }
            return true;
        } else {
            return false;
        }
    }

    protected void baseform() throws CharacterCodingException {
        CharSequence term = new String(termAtt.buffer(), 0, termAtt.length());
        CharSequence s = dictionary.lookup(term);
        if (s != null && s.length() > 0) {
            PackedTokenAttributeImpl impl = new PackedTokenAttributeImpl();
            impl.append(s);
            tokens.add(impl);
        }
    }

    @Override
    public void reset() throws IOException {
        super.reset();
        tokens.clear();
        current = null;
    }

}
