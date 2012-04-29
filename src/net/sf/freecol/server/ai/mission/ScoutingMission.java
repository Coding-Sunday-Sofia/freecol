/**
 *  Copyright (C) 2002-2012   The FreeCol Team
 *
 *  This file is part of FreeCol.
 *
 *  FreeCol is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  FreeCol is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with FreeCol.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.sf.freecol.server.ai.mission;

import java.util.ArrayList;
import java.util.logging.Logger;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;

import net.sf.freecol.common.model.Colony;
import net.sf.freecol.common.model.EquipmentType;
import net.sf.freecol.common.model.FreeColGameObject;
import net.sf.freecol.common.model.IndianSettlement;
import net.sf.freecol.common.model.Location;
import net.sf.freecol.common.model.Map.Direction;
import net.sf.freecol.common.model.PathNode;
import net.sf.freecol.common.model.Player;
import net.sf.freecol.common.model.Settlement;
import net.sf.freecol.common.model.Tension;
import net.sf.freecol.common.model.Tile;
import net.sf.freecol.common.model.Unit;
import net.sf.freecol.common.model.pathfinding.CostDeciders;
import net.sf.freecol.common.model.pathfinding.GoalDecider;
import net.sf.freecol.server.ai.AIMain;
import net.sf.freecol.server.ai.AIMessage;
import net.sf.freecol.server.ai.AIUnit;


/**
 * Mission for controlling a scout.
 *
 * @see net.sf.freecol.common.model.Unit.Role#SCOUT
 */
public class ScoutingMission extends Mission {

    private static final Logger logger = Logger.getLogger(ScoutingMission.class.getName());

    private static final String tag = "AI scout";

    /**
     * Maximum number of turns to travel to a scouting target.
     */
    private static final int MAX_TURNS = 20;

    /**
     * The target for this mission.  Either a tile with an LCR, a
     * native settlement to talk to the chief of, or a player colony
     * to retarget from.
     */
    private Location target = null;


    /**
     * Creates a mission for the given <code>AIUnit</code>.
     *
     * @param aiMain The main AI-object.
     * @param aiUnit The <code>AIUnit</code> this mission is created for.
     */
    public ScoutingMission(AIMain aiMain, AIUnit aiUnit) {
        super(aiMain, aiUnit);

        target = findTarget(aiUnit);
        logger.finest(tag + " starts at " + aiUnit.getUnit().getLocation()
            + " with target " + target + ": " + aiUnit.getUnit());
        uninitialized = false;
    }

    /**
     * Creates a new <code>ScoutingMission</code> and reads the given element.
     *
     * @param aiMain The main AI-object.
     * @param in The input stream containing the XML.
     * @throws XMLStreamException if a problem was encountered during parsing.
     * @see net.sf.freecol.server.ai.AIObject#readFromXML
     */
    public ScoutingMission(AIMain aiMain, XMLStreamReader in)
        throws XMLStreamException {
        super(aiMain);

        readFromXML(in);
        uninitialized = getAIUnit() == null;
    }


    /**
     * Gets the object we are trying to destroy.
     *
     * @return The object which should be destroyed.
     */
    @Override
    public Location getTarget() {
        return target;
    }


    /**
     * Is this a valid scouting target because it is a suitable native
     * settlement.
     *
     * @param aiUnit The <code>AIUnit</code> to test.
     * @param target The target to test.
     * @return True if the target is a valid scouting target.
     */
    private static boolean isIndianSettlementTarget(AIUnit aiUnit,
                                                    Location target) {
        if (!(target instanceof IndianSettlement)) return false;
        IndianSettlement settlement = (IndianSettlement)target;
        final Player owner = aiUnit.getUnit().getOwner();
        Tension tension = settlement.getAlarm(owner);
        return !settlement.hasSpokenToChief(owner)
            && (tension == null
                || tension.getValue() < Tension.Level.HATEFUL.getLimit());
    }

    /**
     * Is this a valid scouting target because it is one of our
     * connected colonies.
     *
     * @param aiUnit The <code>AIUnit</code> to test.
     * @param target The target to test.
     * @return True if the target is a valid scouting target.
     */
    private static boolean isOurColonyTarget(AIUnit aiUnit, Location target) {
        if (!(target instanceof Colony)) return false;
        Colony colony = (Colony)target;
        return colony.isConnected() && aiUnit.getUnit().getOwner().owns(colony);
    }

    /**
     * Is a tile a valid scouting target for a unit?
     *
     * @param aiUnit The <code>AIUnit</code> to test.
     * @param target The target to test.
     * @return True if the target is a valid scouting target.
     */
    public static boolean isTarget(AIUnit aiUnit, Location target) {
        return target != null
            && ((target instanceof Tile
                    && ((Tile)target).hasLostCityRumour())
                || isIndianSettlementTarget(aiUnit, target)
                || isOurColonyTarget(aiUnit, target));
    }

    /**
     * Extract a valid target for this mission from a path.
     *
     * @param aiUnit A <code>AIUnit</code> to perform the mission.
     * @param path A <code>PathNode</code> to extract a target from,
     *     (uses the unit location if null).
     * @return A target for this mission, or null if none found.
     */
    public static Location extractTarget(AIUnit aiUnit, PathNode path) {
        final Tile tile = (path == null) ? aiUnit.getUnit().getTile()
            : path.getLastNode().getTile();
        return (tile == null) ? null
            : (isTarget(aiUnit, tile)) ? tile
            : (isTarget(aiUnit, tile.getSettlement())) ? tile.getSettlement()
            : null;
    }

    /**
     * Evaluate a potential scouting mission for a given unit and
     * path.
     *
     * @param aiUnit The <code>AIUnit</code> to do the mission.
     * @param path A <code>PathNode</code> to take to the target.
     * @return A score for the proposed mission.
     */
    public static int scorePath(AIUnit aiUnit, PathNode path) {
        Location target;
        return (path == null
            || (target = extractTarget(aiUnit, path)) == null
            || target instanceof Colony) ? Integer.MIN_VALUE
            : 1000 / (path.getTotalTurns() + 1);
    }

    /**
     * Finds a suitable scouting target for the supplied unit.
     *
     * @param aiUnit The <code>AIUnit</code> to test.
     * @return A <code>PathNode</code> to the target, or null if none found.
     */
    public static PathNode findTargetPath(final AIUnit aiUnit) {
        Unit unit;
        Tile startTile;
        if (aiUnit == null
            || (unit = aiUnit.getUnit()) == null || unit.isDisposed()
            || (startTile = unit.getPathStartTile()) == null) return null;

        final Unit carrier = unit.getCarrier();
        final GoalDecider scoutingDecider
            = getMissionGoalDecider(aiUnit, ScoutingMission.class);
        PathNode path;

        // Can the scout legally reach a valid target from where it
        // currently is?
        path = unit.search(startTile, scoutingDecider,
                           CostDeciders.avoidIllegal(), MAX_TURNS, carrier);
        if (path != null) return path;

        // If no target was found but there is a carrier, then give up
        // as we should have been able to get everywhere except
        // islands in lakes.
        if (carrier != null) {
            logger.finest(tag + " (with carrier) out of targets: " + unit);
            return null;
        }

        // Search again, purely on distance in tiles, which allows
        // water tiles and thus potentially finds targets that require
        // a carrier to reach.
        return unit.search(startTile, scoutingDecider,
                           CostDeciders.numberOfTiles(), MAX_TURNS, carrier);
    }

    /**
     * Finds a suitable scouting target for the supplied unit.
     * Falls back to the best settlement if the unit is not on the map.
     *
     * @param aiUnit The <code>AIUnit</code> to test.
     * @return A <code>PathNode</code> to the target, or null if none found.
     */
    public static Location findTarget(AIUnit aiUnit) {
        PathNode path = findTargetPath(aiUnit);
        return (path == null) ? getBestSettlement(aiUnit.getUnit().getOwner())
            : extractTarget(aiUnit, path);
    }        


    // Fake Transportable interface

    /**
     * Gets the transport destination for units with this mission.
     *
     * @return The destination for this <code>Transportable</code>.
     */
    @Override
    public Location getTransportDestination() {
        return (target == null
            || !shouldTakeTransportToTile(target.getTile())) ? null
            : target;
    }


    // Mission interface

    /**
     * Checks if it is possible to assign a valid scouting mission to
     * a unit.
     *
     * @param aiUnit The <code>AIUnit</code> to be checked.
     * @return True if the unit could be a scout.
     */
    public static boolean isValid(AIUnit aiUnit) {
        return Mission.isValid(aiUnit)
            && aiUnit.getUnit().getRole() == Unit.Role.SCOUT
            && findTarget(aiUnit) != null;
    }

    /**
     * Checks if this mission is still valid.
     * Allows for a backup colony target.
     *
     * @return True if this mission is still valid.
     */
    public boolean isValid() {
        return super.isValid()
            && getUnit().getRole() == Unit.Role.SCOUT
            && isTarget(getAIUnit(), target);
    }

    /**
     * Performs this mission.
     */
    public void doMission() {
        final Unit unit = getUnit();
        if (unit == null || unit.isDisposed()) {
            logger.warning(tag + " broken: " + unit);
            return;
        } else if (unit.getRole() != Unit.Role.SCOUT) {
            logger.warning(tag + " dismounted: " + unit);
            return;
        }

        // Check the target.
        final AIUnit aiUnit = getAIUnit();
        if (!isTarget(aiUnit, target)) {
            if ((target = findTarget(aiUnit)) == null) {
                logger.finest(tag + " could not find a target: " + unit);
                return;
            }
        }

        // Go there.
        Unit.MoveType mt = travelToTarget(tag, target);
        switch (mt) {
        case ATTACK_UNIT: case MOVE_NO_MOVES: case MOVE_NO_REPAIR:
        case MOVE_ILLEGAL:
            return;
        case MOVE:
            break;
        case ENTER_INDIAN_SETTLEMENT_WITH_SCOUT:
            Direction d = unit.getTile().getDirection(target.getTile());
            if (d == null) {
                throw new IllegalStateException("Unit not next to target "
                    + target + ": " + unit + "/" + unit.getLocation());
            }
            AIMessage.askScoutIndianSettlement(aiUnit, d);
            if (unit.isDisposed()) {
                logger.finest(tag + " died at target " + target
                    + ": " + unit);
                return;
            }
            break;
        default:
            logger.warning(tag + " unexpected move type " + mt + ": " + unit);
            return;
        }

        // Retarget when complete, but do not retarget from one colony
        // to another (just drop equipment and invalidate the mission).
        Location completed = target;
        target = findTarget(aiUnit);
        if (isOurColonyTarget(aiUnit, completed)
            && isOurColonyTarget(aiUnit, target)) {
            Colony colony = (Colony)completed;
            for (EquipmentType e : new ArrayList<EquipmentType>(unit
                    .getEquipment().keySet())) {
                int n = unit.getEquipmentCount(e);
                unit.changeEquipment(e, -n);
                colony.addEquipmentGoods(e, n); // TODO: check for overflow
            }
            target = null;
        }
        logger.finest(tag + " completed target " + completed
            + ", retargeting " + target + ": " + unit);
    }


    // Serialization

    /**
     * Writes all of the <code>AIObject</code>s and other AI-related
     * information to an XML-stream.
     *
     * @param out The target stream.
     * @throws XMLStreamException if there are any problems writing to the
     *             stream.
     */
    protected void toXMLImpl(XMLStreamWriter out) throws XMLStreamException {
        toXML(out, getXMLElementTagName());
    }

    /**
     * {@inherit-doc}
     */
    protected void writeAttributes(XMLStreamWriter out)
        throws XMLStreamException {
        super.writeAttributes(out);
        if (target != null) {
            writeAttribute(out, "target", (FreeColGameObject)target);
        }
    }

    /**
     * {@inherit-doc}
     */
    protected void readAttributes(XMLStreamReader in)
        throws XMLStreamException {
        super.readAttributes(in);

        String str = in.getAttributeValue(null, "target");
        target = getGame().getFreeColLocation(str);
    }

    /**
     * Returns the tag name of the root element representing this object.
     *
     * @return "scoutingMission".
     */
    public static String getXMLElementTagName() {
        return "scoutingMission";
    }
}
