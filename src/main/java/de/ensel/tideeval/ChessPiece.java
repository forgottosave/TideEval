/*
 * Copyright (c) 2021.
 * Feel free to use code or algorithms, but always keep this copyright notice attached with my name -> Christian Ensel
 */

package de.ensel.tideeval;

import java.util.*;

import static de.ensel.tideeval.ChessBasics.*;
import static de.ensel.tideeval.ChessBoard.*;
import static java.lang.Math.abs;
import static java.lang.Math.min;

public class ChessPiece {
    static long debug_propagationCounter = 0;
    static long debug_updateMobilityCounter = 0;

    private final ChessBoard board;
    private final int myPceType;
    private final int myPceID;
    private int myPos;
    private long latestUpdate;   // virtual "time"stamp (=consecutive number) for last/ongoing update.

    /** The Pieces mobility (=nr of squares it can safely go) on the first max three hops.
     *  Must  always be updated right after correction of relEvals.
     *  Index corresponds to the dist.  (mobilityFor3Hops[3] is nr of squares reachable with three hops)
     *  BUT: dist==1 is split into [0]->dist==1 and no condition, [1]->dist==1 but conditional
     */
    private final int[] mobilityFor3Hops;

    private HashMap<Move,int[]> movesAndChances;   // stores real moves (i.d. d==1) and the chances they have on certain future-levels (thus the Array of relEvals)
    private HashMap<Move,int[]> movesAwayChances;  // stores real moves (i.d. d==1) and the chances they have on certain future-levels concerning moving the Piece away from its origin
    private int bestRelEvalAt;  // bestRelEval found at dist==1 by moving to this position. ==NOWHERE if no move available

    ChessPiece(ChessBoard myChessBoard, int pceTypeNr, int pceID, int pcePos) {
        this.board = myChessBoard;
        myPceType = pceTypeNr;
        myPceID = pceID;
        myPos = pcePos;
        latestUpdate = 0;
        mobilityFor3Hops = new int[min(4,MAX_INTERESTING_NROF_HOPS)+1];
        resetPieceBasics();
    }

    private void resetPieceBasics() {
        Arrays.fill(mobilityFor3Hops, 0);
        bestRelEvalAt = POS_UNSET;
        clearMovesAndAllChances();
    }

    public int getValue() {
        // Todo calc real/better value of piece
        return getPieceBaseValue(myPceType);
    }

    public HashMap<Move, int[]> getMovesAndChances() {
        if (movesAndChances==null || movesAndChances.size()==0)
            return null;

        return movesAndChances;
    }

    public HashMap<Move, int[]> getLegalMovesAndChances() {
        HashMap<Move, int[]> legalMovesAndChances = new HashMap<>(movesAndChances.size());
        for (Map.Entry<Move, int[]> e : movesAndChances.entrySet() ) {
            if (isBasicallyALegalMoveForMeTo(e.getKey().to())
                && board.moveIsNotBlockedByKingPin(this, e.getKey().to())
                && ( !board.isCheck(color())
                     || board.nrOfChecks(color())==1 && board.posIsBlockingCheck(color(),e.getKey().to())
                     || isKing(myPceType))
            ) {
                legalMovesAndChances.put(e.getKey(), e.getValue());
            }
        }
        return legalMovesAndChances;
    }

    public int getBestMoveRelEval() {
        if (bestRelEvalAt==NOWHERE)
            return isWhite() ? WHITE_IS_CHECKMATE : BLACK_IS_CHECKMATE;
        if (bestRelEvalAt==POS_UNSET)
            return NOT_EVALUATED;
        return board.getBoardSquares()[bestRelEvalAt].getvPiece(myPceID).getRelEval();
    }

    /**
     * getSimpleMobilities()
     * @return int[] for mobility regarding hopdistance i (not considering whether there is chess at the moment)
     * - i.e. result[0] reflects how many moves the piece can make fro where it stands.
     * - result[1] is how many squares can be reached in 2 hops
     *   (so one move of the above + one more OR because another figure moves out of the way)
     */
    int[] getSimpleMobilities() {
        // TODO: discriminate between a) own figure in the way (which i can control) or uncovered opponent (which I can take)
        // and b) opponent piece blocking the way (but which also "pins" it there to keep it up)
        int[] mobilityCountForHops = new int[MAX_INTERESTING_NROF_HOPS];
        for( Square sq : board.getBoardSquares() ) {
            int distance = sq.getDistanceToPieceId(myPceID);
            if (distance!=0 && distance<=MAX_INTERESTING_NROF_HOPS)
                mobilityCountForHops[distance-1]++;
        }
        return mobilityCountForHops;
    }


    /** Mobility counters need to be updated with every update wave, i.e. right after recalc of relEvals
     *
      */
    public void updateMobilityInklMoves() {
        // little Optimization:
        // the many calls to here lead to about 15-18 sec longer for the overall ~90 sec for the boardEvaluation_Test()
        // for the std. 400 games on my current VM -> so almost 20%... with the following optimization it is reduced to
        // about +6 sec. Much better. Result is not exactly the same, it has influence on 0.001% of the evaluated boards
        if (board.currentDistanceCalcLimit() > mobilityFor3Hops.length) {
            // for optimization, we assume that if the current Distanc-Calc limit is already above the number we are
            // interested in, then nothing will change for the counts of the lower mobilities
            return;
        }
        boolean doUpdateMoveLists = (board.currentDistanceCalcLimit() == mobilityFor3Hops.length);

        // the following does not improve anything at the moment, as one of the vPves of the ChessPies has always changed
        // ... would need a more intelligent dirty-call from the vPcesa
        // if (bestRelEvalAt!=POS_UNSET)
        //    return;  // not dirty or new, so we trust the value still

        debug_updateMobilityCounter++;

        boolean prevMoveability = canMoveAwayReasonably();

        // clear mobility counter
        Arrays.fill(mobilityFor3Hops, 0);
        bestRelEvalAt = NOWHERE;

        // and re-count - should be replaced by always-up-to-date mechanism
        int bestRelEvalSoFar = isWhite() ? WHITE_IS_CHECKMATE : BLACK_IS_CHECKMATE;
        for( Square sq : board.getBoardSquares() ) {
            VirtualPieceOnSquare vPce = sq.getvPiece(myPceID);
            ConditionalDistance cd = vPce.getMinDistanceFromPiece();
            int distance = cd.dist();
            if (distance>0
                    && distance<mobilityFor3Hops.length
                    && distance<= board.currentDistanceCalcLimit() ) {
                if (!cd.hasNoGo()) {
                    int targetPceID = sq.getPieceID();
                    if (distance == 1
                            && cd.isUnconditional()
                            && (targetPceID == NO_PIECE_ID
                            || board.getPiece(targetPceID).color() != color())  // has no piece of my own color (test needed, because this has no condition although that piece actually has to go away first)
                    ) {
                        mobilityFor3Hops[0]++;
                        final int relEval = vPce.getRelEval();
                        if (isWhite() ? relEval > bestRelEvalSoFar
                                : relEval < bestRelEvalSoFar) {
                            bestRelEvalSoFar = relEval;
                            bestRelEvalAt = sq.getMyPos();
                        }
                        if (doUpdateMoveLists) {
                            vPce.addChance(relEval, 0);
                            //addVPceMovesAndChances(vPce);
                        }
                        // vPce.addChance(relEval, 1);
                    } else
                        mobilityFor3Hops[distance]++;
                }
                else if (distance==1
                         //&& cd.isUnconditional()
                         && doUpdateMoveLists) {  // although it must have NoGo, it is still a valid move...
                    vPce.addChance(vPce.getRelEval(), 0);
                }
            }
        }
        if (prevMoveability != canMoveAwayReasonably()) {
            // initiate updates/propagations for/from all vPces on this square.
            board.getBoardSquares()[myPos].propagateLocalChange();
        }
    }

    boolean canMoveAwayReasonably() {
        int eval = getBestMoveRelEval();
        if (eval==NOT_EVALUATED)
            return false;
        return (evalIsOkForColByMin(eval, color()));
    }

    boolean canMove() {
        return bestRelEvalAt!=NOWHERE && bestRelEvalAt!=POS_UNSET;
    }


    /**
     * getMobilities()
     * @return int for mobility regarding hopdistance 1-3 (not considering whether there is chess at the moment)
     */
    int getMobilities() {
        int mobSum = mobilityFor3Hops[0];
        for (int i=1; i<mobilityFor3Hops.length; i++)  // MAX_INTERESTING_NROF_HOPS
            mobSum += mobilityFor3Hops[i]>>(i);   // rightshift, so hops==1&conditional counts half, hops==2 counts quater, hops==3 counts only eightth...
        return mobSum;
    }
    /* was:
        {
            // TODO: (see above)
            int[] mobilityCountForHops = new int[MAX_INTERESTING_NROF_HOPS];
            for( Square sq : myChessBoard.getBoardSquares() ) {
                int distance = sq.getDistanceToPieceId(myPceID);
                int relEval = sq.getvPiece(myPceID).getRelEval();
                if (relEval!=NOT_EVALUATED) {
                    if (!isWhite())
                        relEval = -relEval;
                    if (distance!=0 && distance<=MAX_INTERESTING_NROF_HOPS
                            && relEval>=-EVAL_TENTH)
                        mobilityCountForHops[distance-1]++;
                }
            }
            // sum first three levels up into one value, but weight later hops lesser
            int mobSum = mobilityCountForHops[0];
            for (int i=1; i<=2; i++)  // MAX_INTERESTING_NROF_HOPS
                mobSum += mobilityCountForHops[i]>>(i+1);   // rightshift, so hops==2 counts quater, hops==3 counts only eightth...
            return mobSum;
        }
    */

    final int[] getRawMobilities() {
       return mobilityFor3Hops;
    }

    private void clearMovesAndAllChances() {
        // size of 8 is exacly sufficient for all 1hop pieces,
        // but might be too small for slidigPieces on a largely empty board
        clearMovesAndRemoteChancesOnly();
        movesAwayChances = new HashMap<>(8);
    }

    private void clearMovesAndRemoteChancesOnly() {
        movesAndChances = new HashMap<>(8);
    }

    void addVPceMovesAndChances() {
        for (Square sq : board.getBoardSquares()) {
            VirtualPieceOnSquare vPce = sq.getvPiece(myPceID);
            List<HashMap<Move, Integer>> chances = vPce.getChances();
            for (int i = 0; i < chances.size(); i++) {
                HashMap<Move, Integer> chance = chances.get(i);
                for (Map.Entry<Move, Integer> entry : chance.entrySet()) {
                    if (!(entry.getKey() instanceof MoveCondition)) {
                        if ( sq.getMyPos() == getPos() )
                            addMoveAwayChance(entry.getKey(), i, entry.getValue());
                        else
                            addMoveWithChance(entry.getKey(), i, entry.getValue());
                    }
                }
            }
        }
    }

    private void addMoveWithChance(Move move, int futureLevel, int relEval) {
        int[] evalsPerLevel = movesAndChances.get(move);
        if (evalsPerLevel==null) {
            evalsPerLevel = new int[MAX_INTERESTING_NROF_HOPS+1];
            evalsPerLevel[futureLevel] = relEval;
            movesAndChances.put(move, evalsPerLevel);
        } else {
            evalsPerLevel[futureLevel] += relEval;
        }
    }

    private void addMoveAwayChance(Move move, int futureLevel, int relEval) {
        int[] evalsPerLevel = movesAwayChances.get(move);
        if (evalsPerLevel==null) {
            evalsPerLevel = new int[MAX_INTERESTING_NROF_HOPS+1];
            evalsPerLevel[futureLevel] = relEval;
            movesAwayChances.put(move, evalsPerLevel);
        } else {
            evalsPerLevel[futureLevel] += relEval;
        }
    }


    /**
     * die() piece is EOL - clean up
     */
    public void die() {
        // little to clean up here...
        myPos = -1;
    }


    /** ordered que  - to implement a breadth search for propagation **/

    private static final int QUE_MAX_DEPTH = MAX_INTERESTING_NROF_HOPS+3;
    private final List<List<Runnable>> searchPropagationQues = new ArrayList<>();
    {
        // prepare List of HashSets
        for (int i=0; i<QUE_MAX_DEPTH+1; i++) {
            searchPropagationQues.add(new ArrayList<>());
        }
    }

    void quePropagation(final int queIndex, final Runnable function) {
        searchPropagationQues.get(Math.min(queIndex, QUE_MAX_DEPTH)).add(function);
    }

    /**
     * Execute one stored function call from the que with lowest available index
     * it respects a calc depth limit and stops with false
     * if it has reached the limit
     * @param depth hop depth limit
     * @return returns if one propagation was executed or not.
     */
    private boolean queCallNext(final int depth) {
        List<Runnable> searchPropagationQue;
        for (int i = 0, quesSize = Math.min(depth, searchPropagationQues.size());
             i <= quesSize; i++) {
            searchPropagationQue = searchPropagationQues.get(i);
            if (searchPropagationQue != null && searchPropagationQue.size() > 0 ) {
                //System.out.print(" (L"+i+")");
                debug_propagationCounter++;
                searchPropagationQue.get(0).run();
                searchPropagationQue.remove(0);
                return true;  // end loop, we only work on one at a time.
            }
        }
        return false;
    }

    /**
     * executes all open distance calculations and propagations up to the
     * boards currentDistanceCalcLimit()
     */
    public void continueDistanceCalc() {
        continueDistanceCalc(board.currentDistanceCalcLimit());
    }

    public void continueDistanceCalc(int depthlimit) {
        int n = 0;
        startNextUpdate();
        while (queCallNext(depthlimit))
            debugPrint(DEBUGMSG_DISTANCE_PROPAGATION, " Que:" + (n++));
        if (n>0)
            debugPrintln(DEBUGMSG_DISTANCE_PROPAGATION, " QueDone: " + n);
        endUpdate();
    }

    /** Orchestrate update of distances for this Piece in all its vPieces after a move by another piece
     * @param frompos from this position
     * @param topos to this one.
     */
    public void updateDueToPceMove(final int frompos, final int topos) {
        startNextUpdate();
        Square[] squares = board.getBoardSquares();
        VirtualPieceOnSquare fromVPce = squares[frompos].getvPiece(myPceID);
        VirtualPieceOnSquare toVPce   = squares[topos].getvPiece(myPceID);

        if (squares[topos].getPieceID()==myPceID) {
            // I myself was the moved piece, so update is handled the old style:
            toVPce.myOwnPieceHasMovedHereFrom(frompos);
        }
        else {
            // for all other pieces, there are two changes on the board:
            ConditionalDistance d1 = fromVPce.getMinDistanceFromPiece();
            ConditionalDistance d2 = toVPce.getMinDistanceFromPiece();

            // depending on if the piece moves towards me or further away, we have to adapt the update order
            VirtualPieceOnSquare startingVPce;
            VirtualPieceOnSquare finalizingVPce;
            if (d1.cdIsSmallerThan(d2)
                    || !(d2.cdIsSmallerThan(d1))
                       && toVPce.isUnavoidableOnShortestPath(frompos,MAX_INTERESTING_NROF_HOPS)) {
                startingVPce = fromVPce;
                finalizingVPce = toVPce;
            } else {
                startingVPce = toVPce;
                finalizingVPce = fromVPce;
            }
            // the main update
            startingVPce.resetDistances();
            startingVPce.recalcRawMinDistanceFromNeighboursAndPropagate();
            // the queued recalcs need to be calced first, othererwise the 2nd step would work on old data
            continueDistanceCalc(MAX_INTERESTING_NROF_HOPS); // TODO-Check: effect on time? + does it break the nice call order of continueDistanceCalcUpTo()?
//TODO-BUG!! Check:solved? see SquareTest.knightNogoDist_ExBugTest() and Test above.
// Reason is here, that Moving Piece can make NoGos and thus prolongen other pieces distances.
// However, here it is assumend that only shortended distances are propagated.

            // then check if that automatically reached the other square
            // but: we do not check, we always call it, because it could be decreasing, while the above call was increasing (and can not switch that)
            // if (finalizingVPce.getLatestChange() != getLatestUpdate()) {
                // sorry, updates also have to be triggered here
                endUpdate();
                startNextUpdate();
                finalizingVPce.resetDistances();
                finalizingVPce.recalcRawMinDistanceFromNeighboursAndPropagate();
            //}
        }
        endUpdate();
    }



    public boolean pawnCanTheoreticallyReach(final int pos) {
        //TODO: should be moved to a subclass e.g. PawnChessPiece
        assert(colorlessPieceType(myPceType)==PAWN);
        int deltaFiles = abs( fileOf(myPos) - fileOf(pos));
        int deltaRanks;
        if (this.isWhite())
            deltaRanks = rankOf(pos)-rankOf(myPos);
        else
            deltaRanks = rankOf(myPos)-rankOf(pos);
        return (deltaFiles<=deltaRanks);
    }


    public long getLatestUpdate() {
        return latestUpdate;
    }

    public long startNextUpdate() {
        latestUpdate = board.nextUpdateClockTick();
        return latestUpdate;
    }

    public void endUpdate() {
    }

    public int getPieceType() {
        return myPceType;
    }

    public int getPieceID() {
        return myPceID;
    }

    @Override
    public String toString() {
        return pieceColorAndName(myPceType);
    }

    public boolean color() {
        return colorOfPieceType(myPceType);
    }

    int getBaseValue() {
        return getPieceBaseValue(myPceType);
    }

    public int getPos() {
        return myPos;
    }

    public void setPos(int pos) {
        myPos = pos;
        resetPieceBasics();
    }

    public boolean isWhite() {
        return ChessBasics.isPieceTypeWhite(myPceType);
    }

    public char symbol() {
        return fenCharFromPceType(getPieceType());
    }

    public String getMovesAndChancesDescription() {
        StringBuilder res = new StringBuilder(""+movesAndChances.size()+" moves: " );
        for ( Map.Entry<Move,int[]> m : movesAndChances.entrySet() ) {
            res.append(" " + m.getKey()+"=");
            for ( int eval : m.getValue() )
                res.append( (eval==0?"":eval)+"/");
        }
        res.append(" therein for moving away: ");
        for ( Map.Entry<Move,int[]> m : movesAwayChances.entrySet() ) {
            res.append(" " + m.getKey()+"=");
            for ( int eval : m.getValue() )
                res.append( (eval==0?"":eval)+"/");
        }
        return new String(res);
    }

    public int staysEval() {
        return board.getBoardSquares()[myPos].clashEval(); // .getvPiece(myPceID).getRelEval();
    }

    public void collectMoves() {
        clearMovesAndAllChances();
        // collect moves and their chances from all vPces
        for (Square sq : board.getBoardSquares()) {
            sq.getvPiece(myPceID).resetChances();
            //careful, this also adds illegal moves (needed to keep+calc their clash contributions
            if (sq.getvPiece(myPceID).getRawMinDistanceFromPiece().dist()==1) {
                addMoveWithChance(new Move(myPos,sq.getMyPos()), 0, 0);
            }
        }
    }

    /*
    boolean isBasicallyALegalMoveForMeTo(Square sq) {
        ConditionalDistance rmd = sq.getvPiece(myPceID).getRawMinDistanceFromPiece();
        return rmd.dist() == 1
                && rmd.isUnconditional()
                && !(board.hasPieceOfColorAt(color(), sq.getMyPos())   // not: square blocked by own piece
                     || (isKing(myPceType)                         // and not:  king tries to move to a checked square
                         && sq.countDirectAttacksWithColor(opponentColor(color())) > 0)
                    )
                && !(isPawn(myPceType)
                     && fileOf(sq.getMyPos()) != fileOf(myPos)
                     && !board.hasPieceOfColorAt(opponentColor(color()), sq.getMyPos())
                    );
    }
     */

    boolean isBasicallyALegalMoveForMeTo(int topos) {
        Square sq = board.getBoardSquares()[topos];
        ConditionalDistance rmd = sq.getvPiece(myPceID).getRawMinDistanceFromPiece();
        return rmd.dist() == 1
                && rmd.isUnconditional()
                && !(board.hasPieceOfColorAt(color(), topos)   // not: square blocked by own piece
                     || (isKing(myPceType)                         // and not:  king tries to move to a checked square
                         && sq.countDirectAttacksWithColor(opponentColor(color())) > 0)
                    )
                && !(isPawn(myPceType)
                     && fileOf(topos) != fileOf(myPos)
                     && !board.hasPieceOfColorAt(opponentColor(color()), topos)
                    );
    }

    public void mapLostChances() {
        // calculate into each first move, that it actually looses (or prolongs) the chances of the other first moves
        // Solved with separate MoveAwayChances storage added only at the end: was: moving away benefits are unwantedly eradicated, because they are already calculated into many moves...
        HashMap<Move,int[]> simpleMovesAndChances = movesAndChances;
        clearMovesAndRemoteChancesOnly();
        for (Map.Entry<Move, int[]> m : simpleMovesAndChances.entrySet())  {
            if ( abs(m.getValue()[0]) < checkmateEval(BLACK)-getPieceBaseValue(QUEEN) ) {
                int[] omaxbenefits = new int[m.getValue().length];
                // calc non-negative maximum benefit of the other (hindered/prolonged) moves
                for (Map.Entry<Move, int[]> om : simpleMovesAndChances.entrySet()) {
                    if (m != om
                        && ( (isSlidingPieceType(myPceType)
                                && !dirsAreOnSameAxis(m.getKey().direction(), om.getKey().direction()))
                             || (!isSlidingPieceType(myPceType) ) )
                    ) {
                        int omClashContrib = board.getBoardSquares()[om.getKey().to()].getvPiece(myPceID).getClashContrib();
                        /*if (omClashContrib!=0)
                            System.out.println(om.getKey()+"-clashContrib="+omClashContrib);*/
                        if (isWhite() && omClashContrib > omaxbenefits[0]
                                || !isWhite() && omClashContrib < omaxbenefits[0] )
                            omaxbenefits[0] = omClashContrib;
                        if ( abs(om.getValue()[0]) < checkmateEval(BLACK)-getPieceBaseValue(QUEEN) ) {
                            // if this is a doable move, we also consider its further chances (otherwise only the clash contribution)
                            for (int i = 0; i < m.getValue().length; i++) {
                                // non-precise assumption: benefits of other moves can only come one (back) hop later, so we find their maximimum and subtract that
                                // todo: think about missed move opportunities this more thoroughly :-) e.g. king moving N still has same distance to NW and NE square than before...
                                int val = om.getValue()[i];
                                if (isWhite() &&  val > omaxbenefits[i]
                                    || !isWhite() && val < omaxbenefits[i] )
                                    omaxbenefits[i] = val;
                            }
                        }
                    }
                }
                boolean mySquareIsSafeToComeBack = !isPawn(myPceType ) && evalIsOkForColByMin(staysEval(), color());
                int[] newmbenefit = new int[m.getValue().length];
                for (int i = 0; i < m.getValue().length; i++) {
                    // unprecise assuption: benefits of other moves can only come one (back) hop later, so we find their maximimum and subtract that
                    // todo: think about missed move opportunities this more thoroughly :-) e.g. king moving N still has same distance to NW and NE square than before...
                    int[] maCs = movesAwayChances.get(m.getKey());
                    int maC = maCs!=null ? maCs[i] : 0;
                    newmbenefit[i] = m.getValue()[i]     // original chance
                                     - omaxbenefits[i]   // minus what I loose not choosing the other moves
                                     + (i>0 && mySquareIsSafeToComeBack? omaxbenefits[i-1]:0)   // plus adding that what the other moves can do, I can now still do, but one move later
                                     + maC;              // plus the chance of this move, because the piece moves away
                }
                movesAndChances.put(m.getKey(), newmbenefit);
            }
        }
    }

    public void addMoveAwayChance2AllMovesUnlessToBetween(int benefit, int inOrderNr, int fromPosExcl, int toPosExcl) {
        for ( Map.Entry<Move,int[]> e : movesAndChances.entrySet() ) {
            int to = e.getKey().to();
            if ( !isBetweenFromAndTo(to, fromPosExcl, toPosExcl ) ) {
                debugPrint(DEBUGMSG_MOVEEVAL,"[indirectHelp:" + e.getKey() + "]");
                VirtualPieceOnSquare baseVPce = board.getBoardSquares()[myPos].getvPiece(myPceID);
                if (isBasicallyALegalMoveForMeTo(to))
                    baseVPce.addMoveAwayChance(benefit, inOrderNr,e.getKey());
            }
        }
    }

    /**
     * to be called only for directly (=1 move) reachable positions.
     * For 1hop pieces, this is just the position itself.
     * For sliding pieces, additionally all the squares in between
     * @param pos - target position (excluded)
     * @return list of squares able to block my way to pos, from this piece's myPos (included) to pos excluded
     */
    public int[] allPosOnWayTo(int pos) {
        int[] ret;
        if (isSlidingPieceType(myPceType)) {
            int dir = calcDirFromTo(myPos,pos);
            assert (dir!=NONE);
            ret = new int[distanceBetween(myPos,pos)];
            for (int i=0,p=myPos; p!=pos && i<ret.length; p+=dir, i++)
                ret[i]=p;
        }
        else {
            ret = new int[1];
            ret[0] = myPos;
        }
        return ret;
    }

}
