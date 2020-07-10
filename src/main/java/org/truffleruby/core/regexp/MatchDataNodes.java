/*
 * Copyright (c) 2013, 2019 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.regexp;

import java.util.Arrays;
import java.util.Iterator;

import org.jcodings.Encoding;
import org.joni.NameEntry;
import org.joni.Regex;
import org.joni.Region;
import org.joni.exception.ValueException;
import org.truffleruby.Layouts;
import org.truffleruby.builtins.CoreMethod;
import org.truffleruby.builtins.CoreMethodArrayArgumentsNode;
import org.truffleruby.builtins.CoreModule;
import org.truffleruby.builtins.NonStandard;
import org.truffleruby.builtins.Primitive;
import org.truffleruby.builtins.PrimitiveArrayArgumentsNode;
import org.truffleruby.builtins.UnaryCoreMethodNode;
import org.truffleruby.core.array.ArrayHelpers;
import org.truffleruby.core.array.ArrayIndexNodes;
import org.truffleruby.core.array.ArrayOperations;
import org.truffleruby.core.array.ArrayUtils;
import org.truffleruby.core.cast.IntegerCastNode;
import org.truffleruby.core.cast.ToIntNode;
import org.truffleruby.core.regexp.MatchDataNodesFactory.ValuesNodeFactory;
import org.truffleruby.core.rope.Rope;
import org.truffleruby.core.rope.RopeNodes;
import org.truffleruby.core.string.StringOperations;
import org.truffleruby.core.string.StringSupport;
import org.truffleruby.core.string.StringUtils;
import org.truffleruby.core.symbol.RubySymbol;
import org.truffleruby.language.Nil;
import org.truffleruby.language.NotProvided;
import org.truffleruby.language.RubyNode;
import org.truffleruby.language.Visibility;
import org.truffleruby.language.control.RaiseException;
import org.truffleruby.language.dispatch.CallDispatchHeadNode;
import org.truffleruby.language.library.RubyLibrary;
import org.truffleruby.language.objects.AllocateObjectNode;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.profiles.ConditionProfile;

@CoreModule(value = "MatchData", isClass = true)
public abstract class MatchDataNodes {

    @TruffleBoundary
    public static Object begin(DynamicObject matchData, int index) {
        // Taken from org.jruby.RubyMatchData
        int b = Layouts.MATCH_DATA.getRegion(matchData).beg[index];

        if (b < 0) {
            return Nil.INSTANCE;
        }

        final Rope rope = StringOperations.rope(Layouts.MATCH_DATA.getSource(matchData));
        if (!rope.isSingleByteOptimizable()) {
            b = getCharOffsets(matchData).beg[index];
        }

        return b;
    }

    @TruffleBoundary
    public static Object end(DynamicObject matchData, int index) {
        // Taken from org.jruby.RubyMatchData
        int e = Layouts.MATCH_DATA.getRegion(matchData).end[index];

        if (e < 0) {
            return Nil.INSTANCE;
        }

        final Rope rope = StringOperations.rope(Layouts.MATCH_DATA.getSource(matchData));
        if (!rope.isSingleByteOptimizable()) {
            e = getCharOffsets(matchData).end[index];
        }

        return e;
    }

    @TruffleBoundary
    private static void updatePairs(Rope source, Encoding encoding, Pair[] pairs) {
        // Taken from org.jruby.RubyMatchData
        Arrays.sort(pairs);

        int length = pairs.length;
        byte[] bytes = source.getBytes();
        int p = 0;
        int s = p;
        int c = 0;

        for (int i = 0; i < length; i++) {
            int q = s + pairs[i].bytePos;
            c += StringSupport.strLength(encoding, bytes, p, q);
            pairs[i].charPos = c;
            p = q;
        }
    }

    private static Region getCharOffsetsManyRegs(DynamicObject matchData, Rope source, Encoding encoding) {
        // Taken from org.jruby.RubyMatchData
        final Region regs = Layouts.MATCH_DATA.getRegion(matchData);
        int numRegs = regs.numRegs;

        final Region charOffsets = new Region(numRegs);

        if (encoding.maxLength() == 1) {
            for (int i = 0; i < numRegs; i++) {
                charOffsets.beg[i] = regs.beg[i];
                charOffsets.end[i] = regs.end[i];
            }
            return charOffsets;
        }

        Pair[] pairs = new Pair[numRegs * 2];
        for (int i = 0; i < pairs.length; i++) {
            pairs[i] = new Pair();
        }

        int numPos = 0;
        for (int i = 0; i < numRegs; i++) {
            if (regs.beg[i] < 0) {
                continue;
            }
            pairs[numPos++].bytePos = regs.beg[i];
            pairs[numPos++].bytePos = regs.end[i];
        }

        updatePairs(source, encoding, pairs);

        Pair key = new Pair();
        for (int i = 0; i < regs.numRegs; i++) {
            if (regs.beg[i] < 0) {
                charOffsets.beg[i] = charOffsets.end[i] = -1;
                continue;
            }
            key.bytePos = regs.beg[i];
            charOffsets.beg[i] = pairs[Arrays.binarySearch(pairs, key)].charPos;
            key.bytePos = regs.end[i];
            charOffsets.end[i] = pairs[Arrays.binarySearch(pairs, key)].charPos;
        }

        return charOffsets;
    }

    public static Region getCharOffsets(DynamicObject matchData) {
        // Taken from org.jruby.RubyMatchData
        Region charOffsets = Layouts.MATCH_DATA.getCharOffsets(matchData);
        if (charOffsets != null) {
            return charOffsets;
        } else {
            return createCharOffsets(matchData);
        }
    }

    @TruffleBoundary
    private static Region createCharOffsets(DynamicObject matchData) {
        final Rope source = StringOperations.rope(Layouts.MATCH_DATA.getSource(matchData));
        final Encoding enc = source.getEncoding();
        final Region charOffsets = getCharOffsetsManyRegs(matchData, source, enc);
        Layouts.MATCH_DATA.setCharOffsets(matchData, charOffsets);
        return charOffsets;
    }

    @Primitive(name = "matchdata_create_single_group", lowerFixnum = { 2, 3 })
    public abstract static class MatchDataCreateSingleGroupNode extends PrimitiveArrayArgumentsNode {

        @Specialization
        protected Object create(DynamicObject regexp, DynamicObject string, int start, int end,
                @Cached AllocateObjectNode allocateNode) {
            final Region region = new Region(start, end);
            return allocateNode.allocate(
                    coreLibrary().matchDataClass,
                    Layouts.MATCH_DATA.build(string, regexp, region, null));
        }

    }

    @Primitive(name = "matchdata_create")
    public abstract static class MatchDataCreateNode extends PrimitiveArrayArgumentsNode {

        @Specialization
        protected Object create(DynamicObject regexp, DynamicObject string, DynamicObject starts, DynamicObject ends,
                @Cached AllocateObjectNode allocateNode,
                @Cached ArrayIndexNodes.ReadNormalizedNode readNode,
                @Cached IntegerCastNode integerCastNode) {
            final Region region = new Region(ArrayHelpers.getSize(starts));
            for (int i = 0; i < region.numRegs; i++) {
                region.beg[i] = integerCastNode.executeCastInt(readNode.executeRead(starts, i));
                region.end[i] = integerCastNode.executeCastInt(readNode.executeRead(ends, i));
            }

            return allocateNode.allocate(
                    coreLibrary().matchDataClass,
                    Layouts.MATCH_DATA.build(string, regexp, region, null));
        }

    }

    @CoreMethod(
            names = "[]",
            required = 1,
            optional = 1,
            lowerFixnum = { 1, 2 },
            taintFrom = 0,
            argumentNames = { "index_start_range_or_name", "length" })
    public abstract static class GetIndexNode extends CoreMethodArrayArgumentsNode {

        @Child private RegexpNode regexpNode;
        @Child private ValuesNode getValuesNode = ValuesNode.create();
        @Child private RopeNodes.SubstringNode substringNode = RopeNodes.SubstringNode.create();
        @Child private AllocateObjectNode allocateNode = AllocateObjectNode.create();

        public static GetIndexNode create(RubyNode... nodes) {
            return MatchDataNodesFactory.GetIndexNodeFactory.create(nodes);
        }

        protected abstract Object executeGetIndex(Object matchData, int index, NotProvided length);

        @Specialization
        protected Object getIndex(DynamicObject matchData, int index, NotProvided length,
                @Cached ConditionProfile normalizedIndexProfile,
                @Cached ConditionProfile indexOutOfBoundsProfile,
                @Cached ConditionProfile hasValueProfile) {
            final DynamicObject source = Layouts.MATCH_DATA.getSource(matchData);
            final Rope sourceRope = StringOperations.rope(source);
            final Region region = Layouts.MATCH_DATA.getRegion(matchData);
            final int normalizedIndex = ArrayOperations
                    .normalizeIndex(region.beg.length, index, normalizedIndexProfile);

            if (indexOutOfBoundsProfile.profile((normalizedIndex < 0) || (normalizedIndex >= region.beg.length))) {
                return nil;
            } else {
                final int start = region.beg[normalizedIndex];
                final int end = region.end[normalizedIndex];
                if (hasValueProfile.profile(start > -1 && end > -1)) {
                    Rope rope = substringNode.executeSubstring(sourceRope, start, end - start);
                    return allocateNode.allocate(
                            Layouts.BASIC_OBJECT.getLogicalClass(source),
                            Layouts.STRING.build(false, false, rope));
                } else {
                    return nil;
                }
            }
        }

        @Specialization
        protected Object getIndex(DynamicObject matchData, int index, int length) {
            // TODO BJF 15-May-2015 Need to handle negative indexes and lengths and out of bounds
            final Object[] values = getValuesNode.execute(matchData);
            final int normalizedIndex = ArrayOperations.normalizeIndex(values.length, index);
            final Object[] store = Arrays.copyOfRange(values, normalizedIndex, normalizedIndex + length);
            return createArray(store);
        }

        @Specialization(
                guards = {
                        "name != null",
                        "getRegexp(matchData) == regexp",
                        "cachedIndex == index" })
        protected Object getIndexSymbolSingleMatch(DynamicObject matchData, RubySymbol index, NotProvided length,
                @Cached("index") RubySymbol cachedIndex,
                @Cached("getRegexp(matchData)") DynamicObject regexp,
                @Cached("findNameEntry(regexp, index)") NameEntry name,
                @Cached("numBackRefs(name)") int backRefs,
                @Cached("backRefIndex(name)") int backRefIndex) {
            if (backRefs == 1) {
                return executeGetIndex(matchData, backRefIndex, NotProvided.INSTANCE);
            } else {
                final int i = getBackRef(matchData, regexp, name);
                return executeGetIndex(matchData, i, NotProvided.INSTANCE);
            }
        }

        @Specialization
        protected Object getIndexSymbol(DynamicObject matchData, RubySymbol index, NotProvided length) {
            return executeGetIndex(matchData, getBackRefFromSymbol(matchData, index), NotProvided.INSTANCE);
        }

        @Specialization(guards = "isRubyString(index)")
        protected Object getIndexString(DynamicObject matchData, DynamicObject index, NotProvided length) {
            return executeGetIndex(matchData, getBackRefFromString(matchData, index), NotProvided.INSTANCE);
        }

        @Specialization(
                guards = { "!isInteger(index)", "!isRubySymbol(index)", "!isRubyString(index)", "!isIntRange(index)" })
        protected Object getIndex(DynamicObject matchData, Object index, NotProvided length,
                @Cached ToIntNode toIntNode) {
            return executeGetIndex(matchData, toIntNode.execute(index), NotProvided.INSTANCE);
        }

        @TruffleBoundary
        @Specialization(guards = "isIntRange(range)")
        protected Object getIndex(DynamicObject matchData, DynamicObject range, NotProvided len) {
            final Object[] values = getValuesNode.execute(matchData);
            final int normalizedIndex = ArrayOperations
                    .normalizeIndex(values.length, Layouts.INT_RANGE.getBegin(range));
            final int end = ArrayOperations.normalizeIndex(values.length, Layouts.INT_RANGE.getEnd(range));
            final int exclusiveEnd = ArrayOperations
                    .clampExclusiveIndex(values.length, Layouts.INT_RANGE.getExcludedEnd(range) ? end : end + 1);
            final int length = exclusiveEnd - normalizedIndex;

            return createArray(Arrays.copyOfRange(values, normalizedIndex, normalizedIndex + length));
        }

        @TruffleBoundary
        protected static NameEntry findNameEntry(DynamicObject regexp, RubySymbol symbol) {
            Regex regex = Layouts.REGEXP.getRegex(regexp);
            Rope rope = symbol.getRope();
            if (regex.numberOfNames() > 0) {
                for (Iterator<NameEntry> i = regex.namedBackrefIterator(); i.hasNext();) {
                    final NameEntry e = i.next();
                    if (bytesEqual(rope.getBytes(), rope.byteLength(), e.name, e.nameP, e.nameEnd)) {
                        return e;
                    }
                }
            }
            return null;
        }

        protected DynamicObject getRegexp(DynamicObject matchData) {
            if (regexpNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                regexpNode = insert(RegexpNode.create());
            }
            return regexpNode.executeGetRegexp(matchData);
        }

        @TruffleBoundary
        private int getBackRefFromString(DynamicObject matchData, DynamicObject index) {
            final Rope value = Layouts.STRING.getRope(index);
            return getBackRefFromRope(matchData, index, value);
        }

        @TruffleBoundary
        private int getBackRefFromSymbol(DynamicObject matchData, RubySymbol index) {
            final Rope value = index.getRope();
            return getBackRefFromRope(matchData, index, value);
        }

        private int getBackRefFromRope(DynamicObject matchData, Object index, Rope value) {
            try {
                return Layouts.REGEXP.getRegex(getRegexp(matchData)).nameToBackrefNumber(
                        value.getBytes(),
                        0,
                        value.byteLength(),
                        Layouts.MATCH_DATA.getRegion(matchData));
            } catch (final ValueException e) {
                throw new RaiseException(
                        getContext(),
                        coreExceptions().indexError(
                                StringUtils.format("undefined group name reference: %s", index.toString()),
                                this));
            }
        }

        @TruffleBoundary
        private int getBackRef(DynamicObject matchData, DynamicObject regexp, NameEntry name) {
            return Layouts.REGEXP.getRegex(regexp).nameToBackrefNumber(
                    name.name,
                    name.nameP,
                    name.nameEnd,
                    Layouts.MATCH_DATA.getRegion(matchData));
        }

        @TruffleBoundary
        protected static int numBackRefs(NameEntry name) {
            return name == null ? 0 : name.getBackRefs().length;
        }

        @TruffleBoundary
        protected static int backRefIndex(NameEntry name) {
            return name == null ? 0 : name.getBackRefs()[0];
        }

        @TruffleBoundary
        private static boolean bytesEqual(byte[] bytes, int byteLength, byte[] name, int nameP, int nameEnd) {
            if (bytes == name && nameP == 0 && byteLength == nameEnd) {
                return true;
            } else if (nameEnd - nameP != byteLength) {
                return false;
            } else {
                return ArrayUtils.memcmp(bytes, 0, name, nameP, byteLength) == 0;
            }
        }
    }

    @Primitive(name = "match_data_begin", lowerFixnum = 1)
    public abstract static class BeginNode extends PrimitiveArrayArgumentsNode {

        @Specialization(guards = "inBounds(matchData, index)")
        protected Object begin(DynamicObject matchData, int index) {
            return MatchDataNodes.begin(matchData, index);
        }

        @TruffleBoundary
        @Specialization(guards = "!inBounds(matchData, index)")
        protected Object beginError(DynamicObject matchData, int index) {
            throw new RaiseException(
                    getContext(),
                    coreExceptions().indexError(StringUtils.format("index %d out of matches", index), this));
        }

        protected boolean inBounds(DynamicObject matchData, int index) {
            return index >= 0 && index < Layouts.MATCH_DATA.getRegion(matchData).numRegs;
        }
    }


    public abstract static class ValuesNode extends CoreMethodArrayArgumentsNode {

        @Child private RopeNodes.SubstringNode substringNode = RopeNodes.SubstringNode.create();
        @Child private AllocateObjectNode allocateNode = AllocateObjectNode.create();

        public static ValuesNode create() {
            return ValuesNodeFactory.create(null);
        }

        public abstract Object[] execute(DynamicObject matchData);

        @TruffleBoundary
        @Specialization
        protected Object[] getValuesSlow(DynamicObject matchData,
                @CachedLibrary(limit = "getRubyLibraryCacheLimit()") RubyLibrary rubyLibrary) {
            final DynamicObject source = Layouts.MATCH_DATA.getSource(matchData);
            final Rope sourceRope = StringOperations.rope(source);
            final Region region = Layouts.MATCH_DATA.getRegion(matchData);
            final Object[] values = new Object[region.numRegs];
            boolean isTainted = rubyLibrary.isTainted(source);

            for (int n = 0; n < region.numRegs; n++) {
                final int start = region.beg[n];
                final int end = region.end[n];

                if (start > -1 && end > -1) {
                    Rope rope = substringNode.executeSubstring(sourceRope, start, end - start);
                    DynamicObject string = allocateNode.allocate(
                            Layouts.BASIC_OBJECT.getLogicalClass(source),
                            Layouts.STRING.build(false, isTainted, rope));
                    values[n] = string;
                } else {
                    values[n] = nil;
                }
            }

            return values;
        }

    }

    @CoreMethod(names = "captures")
    public abstract static class CapturesNode extends CoreMethodArrayArgumentsNode {

        @Child private ValuesNode valuesNode = ValuesNode.create();

        @Specialization
        protected DynamicObject toA(DynamicObject matchData) {
            return createArray(getCaptures(valuesNode.execute(matchData)));
        }

        private static Object[] getCaptures(Object[] values) {
            return ArrayUtils.extractRange(values, 1, values.length);
        }
    }

    @Primitive(name = "match_data_end", lowerFixnum = 1)
    public abstract static class EndNode extends PrimitiveArrayArgumentsNode {

        @Specialization(guards = "inBounds(matchData, index)")
        protected Object end(DynamicObject matchData, int index) {
            return MatchDataNodes.end(matchData, index);
        }

        @TruffleBoundary
        @Specialization(guards = "!inBounds(matchData, index)")
        protected Object endError(DynamicObject matchData, int index) {
            throw new RaiseException(
                    getContext(),
                    coreExceptions().indexError(StringUtils.format("index %d out of matches", index), this));
        }

        protected boolean inBounds(DynamicObject matchData, int index) {
            return index >= 0 && index < Layouts.MATCH_DATA.getRegion(matchData).numRegs;
        }
    }

    @NonStandard
    @CoreMethod(names = "byte_begin", required = 1, lowerFixnum = 1)
    public abstract static class ByteBeginNode extends CoreMethodArrayArgumentsNode {

        @Specialization(guards = "inBounds(matchData, index)")
        protected Object byteBegin(DynamicObject matchData, int index) {
            int b = Layouts.MATCH_DATA.getRegion(matchData).beg[index];
            if (b < 0) {
                return nil;
            } else {
                return b;
            }
        }

        protected boolean inBounds(DynamicObject matchData, int index) {
            return index >= 0 && index < Layouts.MATCH_DATA.getRegion(matchData).numRegs;
        }
    }

    @NonStandard
    @CoreMethod(names = "byte_end", required = 1, lowerFixnum = 1)
    public abstract static class ByteEndNode extends CoreMethodArrayArgumentsNode {

        @Specialization(guards = "inBounds(matchData, index)")
        protected Object byteEnd(DynamicObject matchData, int index) {
            int e = Layouts.MATCH_DATA.getRegion(matchData).end[index];
            if (e < 0) {
                return nil;
            } else {
                return e;
            }
        }

        protected boolean inBounds(DynamicObject matchData, int index) {
            return index >= 0 && index < Layouts.MATCH_DATA.getRegion(matchData).numRegs;
        }
    }

    @CoreMethod(names = { "length", "size" })
    public abstract static class LengthNode extends CoreMethodArrayArgumentsNode {

        @Child private ValuesNode getValues = ValuesNode.create();

        @Specialization
        protected int length(DynamicObject matchData) {
            return getValues.execute(matchData).length;
        }

    }

    @CoreMethod(names = "pre_match", taintFrom = 0)
    public abstract static class PreMatchNode extends CoreMethodArrayArgumentsNode {

        @Child private RopeNodes.SubstringNode substringNode = RopeNodes.SubstringNode.create();
        @Child private AllocateObjectNode allocateNode = AllocateObjectNode.create();

        public abstract DynamicObject execute(DynamicObject matchData);

        @Specialization
        protected Object preMatch(DynamicObject matchData) {
            DynamicObject source = Layouts.MATCH_DATA.getSource(matchData);
            Rope sourceRope = StringOperations.rope(source);
            Region region = Layouts.MATCH_DATA.getRegion(matchData);
            int start = 0;
            int length = region.beg[0];
            Rope rope = substringNode.executeSubstring(sourceRope, start, length);
            DynamicObject string = allocateNode
                    .allocate(Layouts.BASIC_OBJECT.getLogicalClass(source), Layouts.STRING.build(false, false, rope));
            return string;
        }
    }

    @CoreMethod(names = "post_match", taintFrom = 0)
    public abstract static class PostMatchNode extends CoreMethodArrayArgumentsNode {

        @Child private RopeNodes.SubstringNode substringNode = RopeNodes.SubstringNode.create();
        @Child private AllocateObjectNode allocateNode = AllocateObjectNode.create();

        public abstract DynamicObject execute(DynamicObject matchData);

        @Specialization
        protected Object postMatch(DynamicObject matchData) {
            DynamicObject source = Layouts.MATCH_DATA.getSource(matchData);
            Rope sourceRope = StringOperations.rope(source);
            Region region = Layouts.MATCH_DATA.getRegion(matchData);
            int start = region.end[0];
            int length = sourceRope.byteLength() - region.end[0];
            Rope rope = substringNode.executeSubstring(sourceRope, start, length);
            DynamicObject string = allocateNode
                    .allocate(Layouts.BASIC_OBJECT.getLogicalClass(source), Layouts.STRING.build(false, false, rope));
            return string;
        }
    }

    @CoreMethod(names = "to_a")
    public abstract static class ToANode extends CoreMethodArrayArgumentsNode {

        @Child ValuesNode valuesNode = ValuesNode.create();

        @Specialization
        protected DynamicObject toA(DynamicObject matchData) {
            Object[] objects = ArrayUtils.copy(valuesNode.execute(matchData));
            return createArray(objects);
        }
    }

    @CoreMethod(names = "regexp")
    public abstract static class RegexpNode extends CoreMethodArrayArgumentsNode {

        public static RegexpNode create() {
            return MatchDataNodesFactory.RegexpNodeFactory.create(null);
        }

        public abstract DynamicObject executeGetRegexp(DynamicObject matchData);

        @Specialization
        protected DynamicObject regexp(DynamicObject matchData,
                @Cached ConditionProfile profile,
                @Cached("createPrivate()") CallDispatchHeadNode stringToRegexp) {
            final DynamicObject value = Layouts.MATCH_DATA.getRegexp(matchData);
            if (profile.profile(Layouts.REGEXP.isRegexp(value))) {
                return value;
            } else {
                final DynamicObject regexp = (DynamicObject) stringToRegexp.call(
                        coreLibrary().truffleTypeModule,
                        "coerce_to_regexp",
                        value,
                        true);
                Layouts.MATCH_DATA.setRegexp(matchData, regexp);
                return regexp;
            }
        }

    }

    // Defined only so that #initialize_copy works for #dup and #clone.
    // MatchData.allocate is undefined, see regexp.rb.
    @CoreMethod(names = { "__allocate__", "__layout_allocate__" }, constructor = true, visibility = Visibility.PRIVATE)
    public abstract static class InternalAllocateNode extends UnaryCoreMethodNode {

        @Specialization
        protected DynamicObject allocate(DynamicObject rubyClass,
                @Cached AllocateObjectNode allocateNode) {
            return allocateNode.allocate(rubyClass, Layouts.MATCH_DATA.build(null, null, null, null));
        }

    }

    @CoreMethod(names = "initialize_copy", required = 1, raiseIfFrozenSelf = true)
    public abstract static class InitializeCopyNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        protected DynamicObject initializeCopy(DynamicObject self, DynamicObject from) {
            if (self == from) {
                return self;
            }

            if (!Layouts.MATCH_DATA.isMatchData(from)) {
                throw new RaiseException(
                        getContext(),
                        coreExceptions().typeError("initialize_copy should take same class object", this));
            }


            Layouts.MATCH_DATA.setSource(self, Layouts.MATCH_DATA.getSource(from));
            Layouts.MATCH_DATA.setRegexp(self, Layouts.MATCH_DATA.getRegexp(from));
            Layouts.MATCH_DATA.setRegion(self, Layouts.MATCH_DATA.getRegion(from));
            Layouts.MATCH_DATA.setCharOffsets(self, Layouts.MATCH_DATA.getCharOffsets(from));
            return self;
        }
    }

    @Primitive(name = "match_data_get_source")
    public abstract static class GetSourceNode extends PrimitiveArrayArgumentsNode {

        @Specialization
        protected DynamicObject getSource(DynamicObject matchData) {
            return Layouts.MATCH_DATA.getSource(matchData);
        }
    }

    public static final class Pair implements Comparable<Pair> {
        int bytePos, charPos;

        @Override
        public int compareTo(Pair pair) {
            return bytePos - pair.bytePos;
        }
    }

}
