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

import de.ensel.chessgui.ChessEngine;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;

import static de.ensel.tideeval.ChessBasics.*;
import static de.ensel.tideeval.ChessBoard.MAX_INTERESTING_NROF_HOPS;
import static de.ensel.tideeval.ChessBoard.NO_PIECE_ID;
import static java.lang.Math.abs;

public class ChessBoardController implements ChessEngine {
    ChessBoard board;

    @Override
    public boolean doMove(String move) {
        return board.doMove(move);
    }

    @Override
    public String getMove() {
        if (board.isGameOver())
            return null;
        //TODO: chessBoard.go();
        // should be replaced by async functions, see interface
        return board.getMove();
    }

    @Override
    public void setBoard(String fen) {
        if (board==null) {
            board = new ChessBoard(chessBasicRes.getString("chessboard.initialName"), fen);
        }
        else {
            if (!board.updateBoardFromFEN(fen) && !fen.equals(FENPOS_STARTPOS)) {
                // seems the fen ins repeated - maybe I answered with an illegal move? try a board reset.
                System.err.println("Board " + board.getBoardFEN() + " was called to update with equal FEN string: " + fen + ".");
                board = new ChessBoard(chessBasicRes.getString("chessboard.initialName"), board.getBoardFEN());
            }
        }
    }

    @Override
    public boolean setParam(String paramName, String value) {
        String param = paramName.toLowerCase(Locale.ROOT);
        switch (paramName) {
            case "hops", "nrofhops" -> {
                ChessBoard.setMAX_INTERESTING_NROF_HOPS(Integer.parseInt(value));
                return true;
            }
            case "engineP1" -> {
                ChessBoard.setEngineP1(Integer.parseInt(value));
                return true;
            }
        }
        return false;
    }

    @Override
    public String getBoard() {
        return board.getBoardFEN();
    }

    @Override
    public HashMap<String,String > getBoardInfo() {
        HashMap<String,String> boardInfo = new HashMap<>();
        boardInfo.put("BoardInfo of:", board.getBoardName().toString() + " {"+board.getBoardHash()+"}");
        //boardInfo.put("Nr. of moves & turn:", ""+chessBoard.getFullMoves()  );
        boardInfo.put("FEN:", board.getBoardFEN());
        boardInfo.put("Game state:", board.getGameState()
                + ( board.isCheck(board.getTurnCol()) ? " "+board.nrOfChecks(board.getTurnCol())+" checks" : "")
                + " --> " + ( board.isGameOver() ? "Game Over" : (" turn: " + colorName(board.getTurnCol()) + "" ) ) );
        boardInfo.put("Attack balance on opponent side, king area / defend own king:", ""
                + board.evaluateOpponentSideAttack() + ", "
                + board.evaluateOpponentKingAreaAttack() + " / "
                + board.evaluateOwnKingAreaDefense());
        boardInfo.put("Evaluation (overall - piece values, max clashes, mobility) -> move sugestion:", ""
                + board.boardEvaluation()+" - "
                + board.boardEvaluation(1) + ", "
                + board.evaluateMaxClashes() + ", "
                + board.boardEvaluation(4)
                + " -> " + board.getBestMove() );
        return boardInfo;
    }

    @Override
    public int getBoardEvaluation() {
        return board.boardEvaluation();
    }

    @Override
    public HashMap<String,String> getSquareInfo(String square, String squareFrom) {
        HashMap<String,String> squareInfo = new HashMap<>();
        int pos = coordinateString2Pos(square);
        int squareFromPos = squareFrom.length()<2 ? pos : coordinateString2Pos(squareFrom);
        int squareFromPceId = board.getPieceIdAt(squareFromPos);
        // basic square name (is now in headline)
        // does it contain a chess piece?
        ChessPiece pce = board.getPieceAt(pos);
        final String pceInfo;
        if (pce!=null) {
            pceInfo = pce.toString();
            squareInfo.put("Square's piece mobility:", "" + pce.getMobilities() + " "+Arrays.toString(pce.getRawMobilities()) );
            squareInfo.put("Square's piece last update:", "" + (pce==null ? "-" : pce.getLatestUpdate() ) );
        }
        else
            pceInfo = chessBasicRes.getString("pieceCharset.empty");
        // squareInfo.put("Piece:",pceInfo);
        Square sq = board.getBoardSquares()[pos];
        //squareInfo.put("SquareId:",""+pos+" = "+ squareName(pos));
        squareInfo.put("Base Value:",""+(pce==null ? "0" : pce.baseValue()));
        squareInfo.put("t_LatestClashUpdate:", ""+sq.getLatestClashResultUpdate());
        if (squareFromPceId!=NO_PIECE_ID) {
            VirtualPieceOnSquare vPce = sq.getvPiece(squareFromPceId);
            squareInfo.put("* Sel. piece's Uncond. Distance:", "" + sq.getUnconditionalDistanceToPieceIdIfShortest(squareFromPceId));
            int d = sq.getDistanceToPieceId(squareFromPceId);
            squareInfo.put("* Sel. piece's Distance:", "" + ( sq.hasNoGoFromPieceId(squareFromPceId) ? -d : d )  );
            squareInfo.put("* Sel. piece's nr. of first moves to here:", "" + ( vPce.getFirstUncondMovesToHere()==null ? "-" : vPce.getFirstUncondMovesToHere().size() ));
            squareInfo.put("* Sel. piece's update age on square:", "" + (board.getUpdateClock() - vPce.getLatestChange()) );
            squareInfo.put("* Sel. piece's shortest cond. in-path from: ", "" + vPce.getShortestInPathDirDescription() );
            squareInfo.put("* Sel. piece's mobility: ", "" + vPce.getMobility() );
            squareInfo.put("* Lowest price for opp. to kill sel. piece on way to here: ", ""
                    + ( vPce.isKillableOnTheWayHere() ? (vPce.isReasonablyKillableOnTheWayHere() ? 2 : -abs(vPce.getLowestPriceToKillOnTheWayHere()) ) : 8 ) );
            squareInfo.put("* Result if sel. piece moves on square:", "" + (vPce.hasRelEval() ? vPce.getRelEval() : "-") );
            squareInfo.put("* Chances on square:", "" + vPce.getClosestChanceReachout() );
            if (pce!=null)
                squareInfo.put("Moves+Evals: ", "" + pce.getMovesAndChancesDescription() );
        }

        // information specific to this square
        squareInfo.put("May block check:",""+ ( (sq.blocksCheckFor(WHITE)? 3:0) + (sq.blocksCheckFor(BLACK)? -2:0)) );
        //squareInfo.put("Attacks by white:",""+ sq.countDirectAttacksWithColor(WHITE) );
        //squareInfo.put("Attacks by black:",""+ sq.countDirectAttacksWithColor(BLACK) );
        squareInfo.put("Clash Eval:",""+sq.clashEval());
        squareInfo.put("Clash Future Eval:",""+ sq.warningLevel() + " " + Arrays.toString(sq.futureClashEval() ) );
        squareInfo.put("Coverage by White:",""+sq.getClosestChanceReachout(WHITE) + " " + sq.getClosestChanceMove(WHITE)
                + " "+sq.getCoverageInfoByColorForLevel(WHITE, 1)
                +" "+sq.getCoverageInfoByColorForLevel(WHITE, 2)
                +( MAX_INTERESTING_NROF_HOPS>3 ? (" "+sq.getCoverageInfoByColorForLevel(WHITE, 3)) : "") );
        squareInfo.put("Coverage by Black:",""+sq.getClosestChanceReachout(BLACK) + " " + sq.getClosestChanceMove(BLACK) + " "
                +sq.getCoverageInfoByColorForLevel(BLACK, 1)
                +" "+sq.getCoverageInfoByColorForLevel(BLACK, 2)
                +( MAX_INTERESTING_NROF_HOPS>3 ? (" "+sq.getCoverageInfoByColorForLevel(BLACK, 3)) : "") );
        squareInfo.put("Latest Update:",""+sq.getLatestClashResultUpdate());

        // distance info for alle pieces in relation to this square
        for (Iterator<ChessPiece> it = board.getPiecesIterator(); it.hasNext(); ) {
            ChessPiece p = it.next();
            if (p != null) {
                int pID = p.getPieceID();
                int distance = sq.getDistanceToPieceId(pID);

                if (distance<ConditionalDistance.INFINITE_DISTANCE)
                    squareInfo.put("z " + p + " ("+pID+") Distance: ",
                                "" + ( sq.hasNoGoFromPieceId(pID) ? -distance : distance )
                                + " (" + sq.getConditionalDistanceToPieceId(pID)
                                + " [+1:" + sq.getvPiece(pID).minDistanceSuggestionTo1HopNeighbour() + "]"
                                    + "," + (sq.getvPiece(pID).hasRelEval()? sq.getvPiece(pID).getRelEval() : "n.e." )
                                + (sq.getvPiece(pID).getClashContribOrZero()==0 ? ""
                                        : "," + (sq.getvPiece(pID).getClashContribOrZero()==NOT_EVALUATED ? "n.e." : sq.getvPiece(pID).getClashContribOrZero()) )
                                    + ", "+ (sq.getvPiece(pID).isKillable()?"k:":"nk:")
                                             + "P="+ sq.getvPiece(pID).getPriceToKill()
                                    + ", "+ (sq.getvPiece(pID).isKillableOnTheWayHere()?"K:":"nK:")
                                        + "lP="+ sq.getvPiece(pID).getLowestPriceToKillOnTheWayHere()
                                    + ", C="+ (sq.getvPiece(pID).getClashContribOrZero()) +" "
                                    + ", m="+ (sq.getvPiece(pID).getMobility()) +")"
//                                    + " from: " + sq.getvPiece(pID).getReducedPathDescription(
                              + " " + sq.getvPiece(pID).getShortestInPathDirDescription()
                              + "1st:" + sq.getvPiece(pID).getFirstUncondMovesToHere()
//                                    + ":" + sq.getvPiece(pID).getBriefPathDescription()
                              //+ " " + sq.getvPiece(pID).getClosestChanceReachout()
                              + ":" + sq.getvPiece(pID).getChances()
                            + " via " + Arrays.toString( sq.getvPiece(pID).getShortestReasonableUnconditionedPredecessors().stream()
                                        .map(pred -> squareName(pred.getMyPos()) ).toArray() )
                            //+ " " + sq.getvPiece(pID).getDistanceDebugDetails()
                    );
            }
        }
        return squareInfo;
    }
}
