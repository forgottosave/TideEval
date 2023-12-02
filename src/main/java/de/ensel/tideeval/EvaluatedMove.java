/*
 *     TideEval - Wired New Chess Algorithm
 *     Copyright (C) 2023 Christian Ensel
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.ensel.tideeval;

import java.util.*;

import static de.ensel.tideeval.ChessBasics.*;
import static de.ensel.tideeval.ChessBoard.*;

public class EvaluatedMove extends Move {
    final Evaluation eval;

    int target = ANY;  // optional target, for which the evaluation is meant - typically used not for partial move evaluations.

    private boolean isCheckGiving = false;

    EvaluatedMove(final int from, final int to) {
        super(from, to);
        eval = new Evaluation();
    }

    EvaluatedMove(Move move, int[] rawEval) {
        super(move);
        eval = new Evaluation(rawEval);
    }

    EvaluatedMove(Move move, Evaluation oeval) {
        super(move);
        eval = new Evaluation(oeval);
    }

    EvaluatedMove(Move move, int[] rawEval, int target) {
        super(move);
        eval = new Evaluation(rawEval);
        setTarget(target);
    }

    EvaluatedMove(Move move, int target) {
        super(move);
        eval = new Evaluation();
        setTarget(target);
    }

    EvaluatedMove(EvaluatedMove evMove) {
        super(evMove);
        eval = new Evaluation(evMove.eval());
    }

    /**
     * adds or substracts to/from an eval on a certain future level (passthrough to Evaluation)
     * beware: is unchecked
     * @param evalValue
     * @param futureLevel the future level from 0..max
     */
    void addEval(int evalValue, int futureLevel) {
        eval.addEval(evalValue,futureLevel);
    }


    void addEval(Evaluation addEval) {
        eval.addEval(addEval);
    }

    @Deprecated
    void addRawEval(int[] eval) {
        for (int i = 0; i< this.eval.getRawEval().length; i++)
            this.eval.addEval(eval[i],i);
    }

    void addEvalAt(int eval, int futureLevel) {
        this.eval.addEval(eval,futureLevel);
    }

    void subtractEvalAt(int eval, int futureLevel) {
        this.eval.addEval(-eval,futureLevel);
    }

    /**
     * calcs and stores the max of this eval and the given other eval individually on all levels
     * @param meval the other evaluation
     */
    public void incEvaltoMaxFor(Evaluation meval, boolean color) {
        eval.incEvaltoMaxFor(meval, color);
    }

    @Override
    public String toString() {
        return "" + super.toString()
                + "=" + eval.toString();
    }

    boolean isBetterForColorThan(boolean color, EvaluatedMove other) {
        boolean probablyBetter = eval.isBetterForColorThan( color, other.eval());
        if (DEBUGMSG_MOVESELECTION) {
            debugPrintln(DEBUGMSG_MOVESELECTION, "=> " + probablyBetter + ". ");
        }
        return probablyBetter;
    }


    static void addEvaluatedMoveToSortedListOfCol(EvaluatedMove evMove, List<EvaluatedMove> sortedTopMoves, boolean color, int maxTopEntries, List<EvaluatedMove> restMoves) {
        int i;
        for (i = sortedTopMoves.size() - 1; i >= 0; i--) {
            if (!evMove.isBetterForColorThan(color, sortedTopMoves.get(i))) {
                // not better, but it was better than the previous, so add below
                if (i < maxTopEntries)
                    sortedTopMoves.add(i + 1, evMove);
                // move lower rest if top list became too big
                while (sortedTopMoves.size() > maxTopEntries) {
                    restMoves.add(
                        sortedTopMoves.remove(maxTopEntries) );
                }
                return;
            }
        }
        //it was best!!
        sortedTopMoves.add(0, evMove);
        // move lower rest if top list became too big
        while (sortedTopMoves.size() > maxTopEntries) {
            restMoves.add(
                sortedTopMoves.remove(maxTopEntries) );
        }
    }

    public Evaluation eval() {
        return eval;
    }

    @Deprecated
    public int[] getRawEval() {
        return eval.getRawEval();
    }

    public int getEvalAt(int futureLevel) {
        return eval.getEvalAt(futureLevel);
    }

    public boolean isCheckGiving() {
        return isCheckGiving;
    }

    public void setIsCheckGiving() {
        isCheckGiving = true;
    }

    public int getTarget() {
        return target;
    }

    public void setTarget(int target) {
        this.target = target;
    }

    @Deprecated
    public void setEval(int[] eval) {
        this.eval.copyFromRaw(eval);
    }


 /*   @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EvaluatedMove)) return false;
        if (!super.equals(o)) return false;
        EvaluatedMove that = (EvaluatedMove) o;
        return getTarget() == that.getTarget() && isCheckGiving() == that.isCheckGiving() && Arrays.equals(getEval(), that.getEval());
    } */

    @Override
    public Integer hashId() {
        return super.hashId() + (getTarget()<<16);
    }

}
