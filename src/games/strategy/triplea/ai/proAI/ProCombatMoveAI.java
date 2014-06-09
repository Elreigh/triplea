package games.strategy.triplea.ai.proAI;

/*
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.PlayerID;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.Properties;
import games.strategy.triplea.ai.proAI.util.LogUtils;
import games.strategy.triplea.ai.proAI.util.ProAttackOptionsUtils;
import games.strategy.triplea.ai.proAI.util.ProBattleUtils;
import games.strategy.triplea.ai.proAI.util.ProMoveUtils;
import games.strategy.triplea.ai.proAI.util.ProTransportUtils;
import games.strategy.triplea.attatchments.TerritoryAttachment;
import games.strategy.triplea.attatchments.UnitAttachment;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.TransportTracker;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import games.strategy.util.CompositeMatchAnd;
import games.strategy.util.Match;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Level;

/**
 * Pro combat move AI.
 * 
 * <ol>
 * <li>Consider which territories to hold better</li>
 * <li>Consider leaving 1 unit in each territory</li>
 * <li>Consider scramble defenses</li>
 * <li>Consider objective value</li>
 * <li>Consider counter attacks vs transports</li>
 * <li>Add naval bombardment</li>
 * <li>Consider convoy zones</li>
 * </ol>
 * 
 * @author Ron Murhammer
 * @since 2014
 */
public class ProCombatMoveAI
{
	public final static double WIN_PERCENTAGE = 95.0;
	
	// Utilities
	private final ProBattleUtils battleUtils;
	private final ProTransportUtils transportUtils;
	private final ProAttackOptionsUtils attackOptionsUtils;
	private final ProMoveUtils moveUtils;
	
	// Current map settings
	private boolean areNeutralsPassableByAir;
	
	// Current data
	private GameData data;
	private PlayerID player;
	private Territory myCapital;
	private List<Territory> allTerritories;
	
	public ProCombatMoveAI(final ProBattleUtils battleUtils, final ProTransportUtils transportUtils, final ProAttackOptionsUtils attackOptionsUtils, final ProMoveUtils moveUtils)
	{
		this.battleUtils = battleUtils;
		this.transportUtils = transportUtils;
		this.attackOptionsUtils = attackOptionsUtils;
		this.moveUtils = moveUtils;
	}
	
	public void doCombatMove(final IMoveDelegate moveDel, final GameData data, final PlayerID player)
	{
		LogUtils.log(Level.FINE, "Starting combat move phase");
		
		// Current data at the start of combat move
		this.data = data;
		this.player = player;
		areNeutralsPassableByAir = (Properties.getNeutralFlyoverAllowed(data) && !Properties.getNeutralsImpassable(data));
		myCapital = TerritoryAttachment.getFirstOwnedCapitalOrFirstUnownedCapital(player, data);
		allTerritories = data.getMap().getTerritories();
		
		// Initialize data containers
		final Map<Territory, ProAttackTerritoryData> attackMap = new HashMap<Territory, ProAttackTerritoryData>();
		final Map<Unit, Set<Territory>> unitAttackMap = new HashMap<Unit, Set<Territory>>();
		final Map<Unit, Set<Territory>> transportAttackMap = new HashMap<Unit, Set<Territory>>();
		final List<ProAmphibData> transportMapList = new ArrayList<ProAmphibData>();
		final Map<Territory, Set<Territory>> landRoutesMap = new HashMap<Territory, Set<Territory>>();
		
		// Find the maximum number of units that can attack each territory
		final CompositeMatchAnd<Territory> myUnitTerritoriesMatch = new CompositeMatchAnd<Territory>(Matches.territoryHasUnitsOwnedBy(player));
		final List<Territory> myUnitTerritories = Match.getMatches(allTerritories, myUnitTerritoriesMatch);
		attackOptionsUtils.findAttackOptions(player, myUnitTerritories, attackMap, unitAttackMap, transportAttackMap, landRoutesMap, transportMapList, new ArrayList<Territory>(),
					new ArrayList<Territory>());
		
		// Determine which territories to attack
		final List<ProAttackTerritoryData> prioritizedTerritories = prioritizeAttackOptions(player, attackMap, unitAttackMap, transportAttackMap);
		determineTerritoriesToAttack(attackMap, unitAttackMap, prioritizedTerritories, transportMapList, transportAttackMap);
		
		// Determine max enemy counter attack units and which territories can be held
		final Map<Territory, ProAttackTerritoryData> enemyAttackMap = new HashMap<Territory, ProAttackTerritoryData>();
		final List<Territory> territoriesToAttack = new ArrayList<Territory>();
		for (final ProAttackTerritoryData t : prioritizedTerritories)
		{
			territoriesToAttack.add(t.getTerritory());
		}
		attackOptionsUtils.findMaxEnemyAttackUnits(player, territoriesToAttack, new ArrayList<Territory>(), enemyAttackMap);
		determineTerritoriesThatCanBeHeld(prioritizedTerritories, attackMap, enemyAttackMap);
		
		// Remove any neutral territories that can't be held
		removeCertainTerritoriesThatCantBeHeld(prioritizedTerritories);
		
		// Determine how many units to attack each territory with
		determineUnitsToAttackWith(attackMap, unitAttackMap, prioritizedTerritories, transportMapList, transportAttackMap);
		
		// Calculate attack routes and perform moves
		final List<Collection<Unit>> moveUnits = new ArrayList<Collection<Unit>>();
		final List<Route> moveRoutes = new ArrayList<Route>();
		moveUtils.calculateMoveRoutes(player, areNeutralsPassableByAir, moveUnits, moveRoutes, attackMap, true);
		moveUtils.doMove(moveUnits, moveRoutes, null, moveDel);
		
		// Calculate amphib attack routes and perform moves
		moveUnits.clear();
		moveRoutes.clear();
		final List<Collection<Unit>> transportsToLoad = new ArrayList<Collection<Unit>>();
		moveUtils.calculateAmphibRoutes(player, moveUnits, moveRoutes, transportsToLoad, attackMap, true);
		moveUtils.doMove(moveUnits, moveRoutes, transportsToLoad, moveDel);
		
		// Log results
		LogUtils.log(Level.FINE, "Logging results");
		logAttackMoves(attackMap, unitAttackMap, transportMapList, prioritizedTerritories, enemyAttackMap);
	}
	
	private List<ProAttackTerritoryData> prioritizeAttackOptions(final PlayerID player, final Map<Territory, ProAttackTerritoryData> attackMap, final Map<Unit, Set<Territory>> unitAttackMap,
				final Map<Unit, Set<Territory>> transportAttackMap)
	{
		LogUtils.log(Level.FINE, "Prioritizing territories that can be attacked");
		
		// Determine if territory can be successfully attacked with max possible attackers
		final Set<Territory> territoriesToRemove = new HashSet<Territory>();
		for (final Territory t : attackMap.keySet())
		{
			// Check if I can win without amphib units and ignore AA since max units might have lots of planes
			final Match<Unit> defendersAndNotAA = new CompositeMatchAnd<Unit>(Matches.enemyUnit(player, data), Matches.UnitIsAAforAnything.invert());
			final List<Unit> defendingUnits = t.getUnits().getMatches(defendersAndNotAA);
			ProBattleResultData result = battleUtils.estimateBattleResults(player, t, attackMap.get(t).getMaxUnits(), defendingUnits);
			attackMap.get(t).setTUVSwing(result.getTUVSwing());
			if (result.getWinPercentage() < WIN_PERCENTAGE)
			{
				// Check amphib units if I can't win without them
				if (!attackMap.get(t).getMaxAmphibUnits().isEmpty())
				{
					final Set<Unit> combinedUnits = new HashSet<Unit>(attackMap.get(t).getMaxUnits());
					combinedUnits.addAll(attackMap.get(t).getMaxAmphibUnits());
					result = battleUtils.estimateBattleResults(player, t, new ArrayList<Unit>(combinedUnits), defendingUnits);
					attackMap.get(t).setTUVSwing(result.getTUVSwing());
					attackMap.get(t).setNeedAmphibUnits(true);
					if (result.getWinPercentage() < WIN_PERCENTAGE)
						territoriesToRemove.add(t);
				}
				else
					territoriesToRemove.add(t);
			}
		}
		
		// Remove territories that can't be successfully attacked
		for (final Territory t : territoriesToRemove)
		{
			final Set<Unit> combinedUnits = new HashSet<Unit>(attackMap.get(t).getMaxUnits());
			combinedUnits.addAll(attackMap.get(t).getMaxAmphibUnits());
			LogUtils.log(Level.FINER, "Removing territory that we can't successfully attack: " + t.getName() + ", maxAttackers=" + combinedUnits.size());
			attackMap.remove(t);
			for (final Set<Territory> territories : unitAttackMap.values())
				territories.remove(t);
			for (final Set<Territory> territories : transportAttackMap.values())
				territories.remove(t);
		}
		
		// Calculate value of attacking territory
		territoriesToRemove.clear();
		final Match<Territory> canMoveLandTerritoryMatch = new CompositeMatchAnd<Territory>(Matches.territoryDoesNotCostMoneyToEnter(data),
					Matches.TerritoryIsPassableAndNotRestrictedAndOkByRelationships(player, data, true, true, false, false, false));
		final Match<Territory> enemyTerritoryWithNoAdjacentAlliedTerritoriesMatch = new CompositeMatchAnd<Territory>(Matches.isTerritoryEnemy(player, data),
					Matches.territoryHasNeighborMatching(data, Matches.isTerritoryAllied(player, data)).invert());
		for (final ProAttackTerritoryData attackTerritoryData : attackMap.values())
		{
			// Get defending units and average tuv swing for attacking (consider neutral territories)
			final Territory t = attackTerritoryData.getTerritory();
			final List<Unit> defendingUnits = t.getUnits().getMatches(Matches.enemyUnit(player, data));
			double TUVSwing = attackTerritoryData.getTUVSwing();
			
			// Set TUVSwing for neutrals
			if (Match.allMatch(Collections.singleton(t), Matches.TerritoryIsNeutralButNotWater))
			{
				final double neutralStrength = battleUtils.estimateStrength(t.getOwner(), t, defendingUnits, new ArrayList<Unit>(), false);
				TUVSwing = -neutralStrength / 5;
				attackTerritoryData.setTUVSwing(TUVSwing);
			}
			
			// Determine if amphib attack
			int isAmphib = 0;
			if (attackTerritoryData.isNeedAmphibUnits())
				isAmphib = 1;
			
			// Determine if there are no defenders for non-amphib attack
			int isEmpty = 0;
			final boolean hasNoDefenders = Match.allMatch(defendingUnits, Matches.UnitIsInfrastructure);
			if (!attackTerritoryData.isNeedAmphibUnits() && (defendingUnits.isEmpty() || hasNoDefenders))
				isEmpty = 1;
			
			// Determine if it is adjacent to my capital
			int isAdjacentToMyCapital = 0;
			if (!data.getMap().getNeighbors(t, Matches.territoryIs(myCapital)).isEmpty())
				isAdjacentToMyCapital = 1;
			
			// Determine if it has a factory
			int isFactory = 0;
			if (t.getUnits().someMatch(Matches.UnitCanProduceUnits))
				isFactory = 1;
			
			// Determine production value and if it is an enemy capital
			int production = 0;
			int isEnemyCapital = 0;
			final TerritoryAttachment ta = TerritoryAttachment.get(t);
			if (ta != null)
			{
				production = ta.getProduction();
				if (ta.isCapital())
					isEnemyCapital = 1;
			}
			
			// Determine enemy neighbor territory production value for non-amphib land territories
			double nearbyEnemyValue = 0;
			if (!attackTerritoryData.isNeedAmphibUnits() && !t.isWater() && t.getOwner().isNull())
			{
				final Set<Territory> nearbyTerritories = data.getMap().getNeighbors(t, canMoveLandTerritoryMatch);
				
				final List<Territory> nearbyEnemyTerritories = Match.getMatches(nearbyTerritories, enemyTerritoryWithNoAdjacentAlliedTerritoriesMatch);
				for (final Territory nearbyEnemyTerritory : nearbyEnemyTerritories)
				{
					final int isNeutral = nearbyEnemyTerritory.getOwner().isNull() ? 1 : 0;
					final int isEnemyFactory = nearbyEnemyTerritory.getUnits().someMatch(Matches.UnitCanProduceUnits) ? 1 : 0;
					final double enemyProduction = TerritoryAttachment.getProduction(nearbyEnemyTerritory);
					nearbyEnemyValue += enemyProduction * (isEnemyFactory + 1) / (isNeutral + 1);
				}
				LogUtils.log(Level.FINER, "Calculated nearby enemy value=" + nearbyEnemyValue + " from " + nearbyEnemyTerritories);
			}
			
			// Calculate attack value for prioritization
			double defendingUnitsSizeMultiplier = (1.0 / (defendingUnits.size() + 1)) + 0.5; // Used to consider how many attackers I need
			if (TUVSwing < 0)
				defendingUnitsSizeMultiplier = 1;
			final double attackValue = (defendingUnitsSizeMultiplier * TUVSwing + 2 * production + 10 * isEmpty + 5 * isFactory + 0.5 * nearbyEnemyValue) * (1 + 4 * isEnemyCapital)
						* (1 + 2 * isAdjacentToMyCapital) * (1 - 0.5 * isAmphib);
			attackTerritoryData.setValue(attackValue);
			if (attackValue < 0)
				territoriesToRemove.add(t);
		}
		
		// Remove territories that don't have a positive attack value
		for (final Territory t : territoriesToRemove)
		{
			LogUtils.log(Level.FINER, "Removing territory that has a negative attack value: " + t.getName() + ", AttackValue=" + attackMap.get(t).getValue());
			attackMap.remove(t);
			for (final Set<Territory> territories : unitAttackMap.values())
			{
				territories.remove(t);
			}
			for (final Set<Territory> territories : transportAttackMap.values())
			{
				territories.remove(t);
			}
		}
		
		// Sort attack territories by value
		final List<ProAttackTerritoryData> prioritizedTerritories = new ArrayList<ProAttackTerritoryData>(attackMap.values());
		Collections.sort(prioritizedTerritories, new Comparator<ProAttackTerritoryData>()
		{
			public int compare(final ProAttackTerritoryData t1, final ProAttackTerritoryData t2)
			{
				final double value1 = t1.getValue();
				final double value2 = t2.getValue();
				return Double.compare(value2, value1);
			}
		});
		
		// Log prioritized territories
		for (final ProAttackTerritoryData attackTerritoryData : prioritizedTerritories)
		{
			LogUtils.log(Level.FINER,
						"AttackValue=" + attackTerritoryData.getValue() + ", TUVSwing=" + attackTerritoryData.getTUVSwing() + ", isAmphib=" + attackTerritoryData.isNeedAmphibUnits() + ", "
									+ attackTerritoryData.getTerritory().getName());
		}
		
		return prioritizedTerritories;
	}
	
	private void determineTerritoriesToAttack(final Map<Territory, ProAttackTerritoryData> attackMap, final Map<Unit, Set<Territory>> unitAttackMap,
				final List<ProAttackTerritoryData> prioritizedTerritories, final List<ProAmphibData> transportMapList, final Map<Unit, Set<Territory>> transportAttackMap)
	{
		LogUtils.log(Level.FINE, "Determine which territories to attack");
		
		// Assign units to territories by prioritization
		int numToAttack = Math.min(1, prioritizedTerritories.size());
		boolean haveRemovedAllAmphibTerritories = false;
		while (true)
		{
			final List<ProAttackTerritoryData> territoriesToTryToAttack = prioritizedTerritories.subList(0, numToAttack);
			LogUtils.log(Level.FINER, "Current number of territories: " + numToAttack);
			tryToAttackTerritories(attackMap, unitAttackMap, territoriesToTryToAttack, transportMapList, transportAttackMap);
			
			// Determine if all attacks are successful
			boolean areSuccessful = true;
			for (final ProAttackTerritoryData patd : territoriesToTryToAttack)
			{
				final Territory t = patd.getTerritory();
				if (patd.getBattleResult() == null)
					patd.setBattleResult(battleUtils.estimateBattleResults(player, t, attackMap.get(t).getUnits()));
				LogUtils.log(Level.FINEST, patd.getResultString() + " with attackers: " + patd.getUnits());
				final double estimate = battleUtils.estimateStrengthDifference(player, t, patd.getUnits());
				if (patd.isCurrentlyWins() || estimate >= patd.getStrengthEstimate())
					continue;
				final ProBattleResultData result = patd.getBattleResult();
				if (result.getWinPercentage() < WIN_PERCENTAGE || !result.isHasLandUnitRemaining())
					areSuccessful = false;
			}
			
			// Determine whether to try more territories, remove a territory, or end
			if (areSuccessful)
			{
				for (final ProAttackTerritoryData patd : territoriesToTryToAttack)
				{
					patd.setCanAttack(true);
					final double estimate = battleUtils.estimateStrengthDifference(player, patd.getTerritory(), patd.getUnits());
					if (estimate < patd.getStrengthEstimate())
						patd.setStrengthEstimate(estimate);
				}
				
				// If already used all transports then remove any remaining amphib territories
				if (!haveRemovedAllAmphibTerritories)
				{
					final Set<Unit> movedTransports = new HashSet<Unit>();
					for (final ProAttackTerritoryData patd : prioritizedTerritories)
					{
						movedTransports.addAll(patd.getAmphibAttackMap().keySet());
						movedTransports.addAll(Match.getMatches(patd.getUnits(), Matches.UnitIsTransport));
					}
					if (movedTransports.size() >= transportMapList.size())
					{
						final List<ProAttackTerritoryData> amphibTerritoriesToRemove = new ArrayList<ProAttackTerritoryData>();
						for (int i = numToAttack; i < prioritizedTerritories.size(); i++)
						{
							if (prioritizedTerritories.get(i).isNeedAmphibUnits())
							{
								amphibTerritoriesToRemove.add(prioritizedTerritories.get(i));
								LogUtils.log(Level.FINER, "Removing amphib territory since already used all transports: " + prioritizedTerritories.get(i).getTerritory().getName());
							}
						}
						prioritizedTerritories.removeAll(amphibTerritoriesToRemove);
						haveRemovedAllAmphibTerritories = true;
					}
				}
				
				// Can attack all territories in list so end
				numToAttack++;
				if (numToAttack > prioritizedTerritories.size())
					break;
			}
			else
			{
				LogUtils.log(Level.FINER, "Removing territory: " + prioritizedTerritories.get(numToAttack - 1).getTerritory().getName());
				prioritizedTerritories.remove(numToAttack - 1);
				// Check if I've tested all territories in list
				boolean testedAll = true;
				for (final ProAttackTerritoryData patd : prioritizedTerritories)
					testedAll = testedAll && patd.isCanAttack();
				if (testedAll)
					break;
				if (numToAttack > prioritizedTerritories.size())
					numToAttack--;
			}
		}
		LogUtils.log(Level.FINER, "Final number of territories: " + (numToAttack - 1));
	}
	
	private void determineTerritoriesThatCanBeHeld(final List<ProAttackTerritoryData> prioritizedTerritories, final Map<Territory, ProAttackTerritoryData> attackMap,
				final Map<Territory, ProAttackTerritoryData> enemyAttackMap)
	{
		// Determine which territories can possibly be held
		LogUtils.log(Level.FINE, "Check if attack territories can be held");
		for (final ProAttackTerritoryData patd : prioritizedTerritories)
		{
			final Territory t = patd.getTerritory();
			
			// Find max remaining defenders
			final Set<Unit> attackingUnits = new HashSet<Unit>(attackMap.get(t).getMaxUnits());
			attackingUnits.addAll(attackMap.get(t).getMaxAmphibUnits());
			final ProBattleResultData result = battleUtils.estimateBattleResults(player, t, new ArrayList<Unit>(attackingUnits));
			final List<Unit> remainingUnitsToDefendWith = Match.getMatches(result.getAverageUnitsRemaining(), Matches.UnitIsAir.invert());
			LogUtils.log(Level.FINER, "Territory=" + t.getName() + ", MyAttackers=" + attackingUnits.size() + ", RemainingUnits=" + remainingUnitsToDefendWith.size());
			
			// Determine counter attack results to see if I can hold it
			if (enemyAttackMap.get(t) != null)
			{
				final Set<Unit> enemyAttackingUnits = new HashSet<Unit>(enemyAttackMap.get(t).getMaxUnits());
				enemyAttackingUnits.addAll(enemyAttackMap.get(t).getMaxAmphibUnits());
				final ProBattleResultData result2 = battleUtils.estimateBattleResults(player, t, new ArrayList<Unit>(enemyAttackingUnits), remainingUnitsToDefendWith, false);
				final boolean canHold = (!result2.isHasLandUnitRemaining() && !t.isWater()) || (result2.getTUVSwing() < 0) || (result2.getWinPercentage() < WIN_PERCENTAGE);
				attackMap.get(t).setCanHold(canHold);
				attackMap.get(t).setMaxEnemyUnits(new ArrayList<Unit>(enemyAttackingUnits));
				LogUtils.log(Level.FINER, "Territory=" + t.getName() + ", CanHold=" + canHold + ", MyDefenders=" + remainingUnitsToDefendWith.size() + ", EnemyAttackers=" + enemyAttackingUnits.size()
							+ ", win%=" + result2.getWinPercentage() + ", EnemyTUVSwing=" + result2.getTUVSwing() + ", hasLandUnitRemaining=" + result2.isHasLandUnitRemaining());
			}
			else
			{
				attackMap.get(t).setCanHold(true);
				LogUtils.log(Level.FINER, "Territory=" + t.getName() + ", CanHold=true");
			}
		}
	}
	
	private void removeCertainTerritoriesThatCantBeHeld(final List<ProAttackTerritoryData> prioritizedTerritories)
	{
		LogUtils.log(Level.FINE, "Remove certain territories that can't be held");
		
		// Loop through all prioritized territories
		for (final Iterator<ProAttackTerritoryData> it = prioritizedTerritories.iterator(); it.hasNext();)
		{
			final ProAttackTerritoryData patd = it.next();
			
			if (!patd.isCanHold() && !patd.getTerritory().isWater())
			{
				final boolean isNeutral = patd.getTerritory().getOwner().isNull();
				if (isNeutral)
				{
					// Remove any neutral territories that can't be held
					LogUtils.log(Level.FINER, "Removing neutral territory that can't be held: " + patd.getTerritory().getName());
					it.remove();
				}
				else if (patd.isNeedAmphibUnits() && patd.getValue() <= 2)
				{
					// Remove amphib territories that aren't worth attacking
					LogUtils.log(Level.FINER, "Removing low value amphib territory that can't be held: " + patd.getTerritory().getName());
					it.remove();
				}
			}
		}
	}
	
	private void determineUnitsToAttackWith(final Map<Territory, ProAttackTerritoryData> attackMap, final Map<Unit, Set<Territory>> unitAttackMap,
				final List<ProAttackTerritoryData> prioritizedTerritories, final List<ProAmphibData> transportMapList, final Map<Unit, Set<Territory>> transportAttackMap)
	{
		LogUtils.log(Level.FINE, "Determine units to attack each territory with");
		
		// Assign units to territories by prioritization
		while (true)
		{
			Map<Unit, Set<Territory>> sortedUnitAttackOptions = tryToAttackTerritories(attackMap, unitAttackMap, prioritizedTerritories, transportMapList, transportAttackMap);
			
			// Re-sort attack options
			sortedUnitAttackOptions = attackOptionsUtils.sortUnitNeededOptions(player, sortedUnitAttackOptions, attackMap);
			
			// Set air units in any territory with no AA (don't move planes to empty territories)
			for (final Iterator<Unit> it = sortedUnitAttackOptions.keySet().iterator(); it.hasNext();)
			{
				final Unit unit = it.next();
				final boolean isAirUnit = UnitAttachment.get(unit.getType()).getIsAir();
				if (!isAirUnit)
					continue; // skip non-air units
				Territory minWinTerritory = null;
				double minWinPercentage = Double.MAX_VALUE;
				for (final Territory t : sortedUnitAttackOptions.get(unit))
				{
					if (attackMap.get(t).getBattleResult() == null)
						attackMap.get(t).setBattleResult(battleUtils.estimateBattleResults(player, t, attackMap.get(t).getUnits()));
					final ProBattleResultData result = attackMap.get(t).getBattleResult();
					if (result.getWinPercentage() < minWinPercentage || (!result.isHasLandUnitRemaining() && minWinTerritory == null))
					{
						final List<Unit> attackingUnits = attackMap.get(t).getUnits();
						final List<Unit> defendingUnits = t.getUnits().getMatches(Matches.enemyUnit(player, data));
						final boolean isOverwhelmingWin = battleUtils.checkForOverwhelmingWin(player, t, attackingUnits, defendingUnits);
						final boolean hasAA = Match.someMatch(defendingUnits, Matches.UnitIsAAforAnything);
						if (!hasAA && !isOverwhelmingWin)
						{
							minWinPercentage = result.getWinPercentage();
							minWinTerritory = t;
						}
					}
				}
				if (minWinTerritory != null)
				{
					attackMap.get(minWinTerritory).addUnit(unit);
					attackMap.get(minWinTerritory).setBattleResult(null);
					it.remove();
				}
			}
			
			// Re-sort attack options
			sortedUnitAttackOptions = attackOptionsUtils.sortUnitNeededOptions(player, sortedUnitAttackOptions, attackMap);
			
			// Find territory that we can try to hold that needs unit
			for (final Iterator<Unit> it = sortedUnitAttackOptions.keySet().iterator(); it.hasNext();)
			{
				final Unit unit = it.next();
				Territory minWinTerritory = null;
				for (final Territory t : sortedUnitAttackOptions.get(unit))
				{
					if (attackMap.get(t).isCanHold())
					{
						// Check if I already have enough attack units to win in round 1
						final List<Unit> attackingUnits = attackMap.get(t).getUnits();
						final List<Unit> defendingUnits = t.getUnits().getMatches(Matches.enemyUnit(player, data));
						final boolean isOverwhelmingWin = battleUtils.checkForOverwhelmingWin(player, t, attackingUnits, defendingUnits);
						if (!isOverwhelmingWin)
						{
							minWinTerritory = t;
							break;
						}
					}
				}
				if (minWinTerritory != null)
				{
					attackMap.get(minWinTerritory).addUnit(unit);
					it.remove();
				}
			}
			
			// Determine if all attacks/defenses are successful
			ProAttackTerritoryData territoryToRemove = null;
			for (final ProAttackTerritoryData patd : prioritizedTerritories)
			{
				final Territory t = patd.getTerritory();
				if (patd.getBattleResult() == null)
					patd.setBattleResult(battleUtils.estimateBattleResults(player, t, attackMap.get(t).getUnits()));
				final ProBattleResultData result = patd.getBattleResult();
				if (result.getWinPercentage() < (WIN_PERCENTAGE - 5) || !result.isHasLandUnitRemaining())
				{
					territoryToRemove = patd;
					LogUtils.log(Level.FINER, "Removing " + patd.getResultString());
				}
				else
				{
					LogUtils.log(Level.FINER, "Attacking " + patd.getResultString());
				}
			}
			
			// Determine whether all attacks are successful or try to hold fewer territories
			if (territoryToRemove == null)
				break;
			else
				prioritizedTerritories.remove(territoryToRemove);
		}
	}
	
	private Map<Unit, Set<Territory>> tryToAttackTerritories(final Map<Territory, ProAttackTerritoryData> attackMap, final Map<Unit, Set<Territory>> unitAttackMap,
				final List<ProAttackTerritoryData> prioritizedTerritories, final List<ProAmphibData> transportMapList, final Map<Unit, Set<Territory>> transportAttackMap)
	{
		// Reset lists
		for (final Territory t : attackMap.keySet())
		{
			attackMap.get(t).getUnits().clear();
			attackMap.get(t).getAmphibAttackMap().clear();
			attackMap.get(t).setBattleResult(null);
		}
		
		// Loop through all units and determine attack options
		final Map<Unit, Set<Territory>> unitAttackOptions = new HashMap<Unit, Set<Territory>>();
		for (final Unit unit : unitAttackMap.keySet())
		{
			// Find number of attack options
			final Set<Territory> canAttackTerritories = new HashSet<Territory>();
			for (final ProAttackTerritoryData attackTerritoryData : prioritizedTerritories)
			{
				if (unitAttackMap.get(unit).contains(attackTerritoryData.getTerritory()))
					canAttackTerritories.add(attackTerritoryData.getTerritory());
			}
			
			// Add units with attack options to map
			if (canAttackTerritories.size() >= 1)
				unitAttackOptions.put(unit, canAttackTerritories);
		}
		
		// Sort units by number of attack options and cost
		Map<Unit, Set<Territory>> sortedUnitAttackOptions = attackOptionsUtils.sortUnitMoveOptions(player, unitAttackOptions);
		
		// Set enough land and sea units in territories to have at least a chance of winning
		for (final Iterator<Unit> it = sortedUnitAttackOptions.keySet().iterator(); it.hasNext();)
		{
			final Unit unit = it.next();
			final boolean isAirUnit = UnitAttachment.get(unit.getType()).getIsAir();
			if (isAirUnit)
				continue; // skip air units
			final TreeMap<Double, Territory> estimatesMap = new TreeMap<Double, Territory>();
			for (final Territory t : sortedUnitAttackOptions.get(unit))
			{
				final double estimate = battleUtils.estimateStrengthDifference(player, t, attackMap.get(t).getUnits());
				estimatesMap.put(estimate, t);
			}
			if (estimatesMap.firstKey() <= 40)
			{
				attackMap.get(estimatesMap.entrySet().iterator().next().getValue()).addUnit(unit);
				it.remove();
			}
		}
		
		// Set non-air units in territories that can be held
		for (final Iterator<Unit> it = sortedUnitAttackOptions.keySet().iterator(); it.hasNext();)
		{
			final Unit unit = it.next();
			final boolean isAirUnit = UnitAttachment.get(unit.getType()).getIsAir();
			if (isAirUnit)
				continue; // skip air units
			Territory minWinTerritory = null;
			double minWinPercentage = WIN_PERCENTAGE;
			for (final Territory t : sortedUnitAttackOptions.get(unit))
			{
				if (!attackMap.get(t).isCurrentlyWins() && attackMap.get(t).isCanHold())
				{
					if (attackMap.get(t).getBattleResult() == null)
						attackMap.get(t).setBattleResult(battleUtils.estimateBattleResults(player, t, attackMap.get(t).getUnits()));
					final ProBattleResultData result = attackMap.get(t).getBattleResult();
					if (result.getWinPercentage() < minWinPercentage || (!result.isHasLandUnitRemaining() && minWinTerritory == null))
					{
						minWinPercentage = result.getWinPercentage();
						minWinTerritory = t;
					}
				}
			}
			if (minWinTerritory != null)
			{
				attackMap.get(minWinTerritory).addUnit(unit);
				attackMap.get(minWinTerritory).setBattleResult(null);
				it.remove();
			}
		}
		
		// Re-sort attack options
		sortedUnitAttackOptions = attackOptionsUtils.sortUnitNeededOptions(player, sortedUnitAttackOptions, attackMap);
		
		// Set naval units in territories
		for (final Iterator<Unit> it = sortedUnitAttackOptions.keySet().iterator(); it.hasNext();)
		{
			final Unit unit = it.next();
			final boolean isSeaUnit = UnitAttachment.get(unit.getType()).getIsSea();
			if (!isSeaUnit)
				continue; // skip non-sea units
			Territory minWinTerritory = null;
			double minWinPercentage = WIN_PERCENTAGE;
			for (final Territory t : sortedUnitAttackOptions.get(unit))
			{
				if (!attackMap.get(t).isCurrentlyWins())
				{
					if (attackMap.get(t).getBattleResult() == null)
						attackMap.get(t).setBattleResult(battleUtils.estimateBattleResults(player, t, attackMap.get(t).getUnits()));
					final ProBattleResultData result = attackMap.get(t).getBattleResult();
					if (result.getWinPercentage() < minWinPercentage || (!result.isHasLandUnitRemaining() && minWinTerritory == null))
					{
						minWinPercentage = result.getWinPercentage();
						minWinTerritory = t;
					}
				}
			}
			if (minWinTerritory != null)
			{
				attackMap.get(minWinTerritory).addUnit(unit);
				attackMap.get(minWinTerritory).setBattleResult(null);
				it.remove();
			}
		}
		
		// Re-sort attack options
		sortedUnitAttackOptions = attackOptionsUtils.sortUnitNeededOptions(player, sortedUnitAttackOptions, attackMap);
		
		// Loop through all my transports and see which territories they can attack from current list
		final Map<Unit, Set<Territory>> transportAttackOptions = new HashMap<Unit, Set<Territory>>();
		for (final Unit unit : transportAttackMap.keySet())
		{
			// Find number of attack options
			final Set<Territory> canAttackTerritories = new HashSet<Territory>();
			for (final ProAttackTerritoryData attackTerritoryData : prioritizedTerritories)
			{
				if (transportAttackMap.get(unit).contains(attackTerritoryData.getTerritory()))
					canAttackTerritories.add(attackTerritoryData.getTerritory());
			}
			if (!canAttackTerritories.isEmpty())
				transportAttackOptions.put(unit, canAttackTerritories);
		}
		
		// Loop through transports with attack options and determine if any naval battle needs it
		final List<Unit> alreadyAttackedWithTransports = new ArrayList<Unit>();
		for (final Unit transport : transportAttackOptions.keySet())
		{
			// Find current naval battle that needs transport if it isn't transporting units
			for (final Territory t : transportAttackOptions.get(transport))
			{
				if (!attackMap.get(t).isCurrentlyWins() && !TransportTracker.isTransporting(transport))
				{
					if (attackMap.get(t).getBattleResult() == null)
						attackMap.get(t).setBattleResult(battleUtils.estimateBattleResults(player, t, attackMap.get(t).getUnits()));
					final ProBattleResultData result = attackMap.get(t).getBattleResult();
					if (result.getWinPercentage() < WIN_PERCENTAGE || !result.isHasLandUnitRemaining())
					{
						attackMap.get(t).addUnit(transport);
						attackMap.get(t).setBattleResult(null);
						alreadyAttackedWithTransports.add(transport);
						LogUtils.log(Level.FINER, "Adding attack transport to: " + t.getName());
						break;
					}
				}
			}
		}
		
		// Loop through all my transports and see which can make amphib attack
		final Map<Unit, Set<Territory>> amphibAttackOptions = new HashMap<Unit, Set<Territory>>();
		for (final ProAmphibData proTransportData : transportMapList)
		{
			// If already used to attack then ignore
			if (alreadyAttackedWithTransports.contains(proTransportData.getTransport()))
				continue;
			
			// Find number of attack options
			final Set<Territory> canAmphibAttackTerritories = new HashSet<Territory>();
			for (final ProAttackTerritoryData attackTerritoryData : prioritizedTerritories)
			{
				if (proTransportData.getTransportMap().containsKey(attackTerritoryData.getTerritory()))
					canAmphibAttackTerritories.add(attackTerritoryData.getTerritory());
			}
			if (!canAmphibAttackTerritories.isEmpty())
				amphibAttackOptions.put(proTransportData.getTransport(), canAmphibAttackTerritories);
		}
		
		// Loop through transports with amphib attack options and determine if any land battle needs it
		for (final Unit transport : amphibAttackOptions.keySet())
		{
			// Find current land battle results for territories that unit can amphib attack
			Territory minWinTerritory = null;
			double minWinPercentage = WIN_PERCENTAGE;
			List<Unit> amphibUnitsToAdd = null;
			for (final Territory t : amphibAttackOptions.get(transport))
			{
				if (!attackMap.get(t).isCurrentlyWins())
				{
					if (attackMap.get(t).getBattleResult() == null)
						attackMap.get(t).setBattleResult(battleUtils.estimateBattleResults(player, t, attackMap.get(t).getUnits()));
					final ProBattleResultData result = attackMap.get(t).getBattleResult();
					if (result.getWinPercentage() < minWinPercentage || !result.isHasLandUnitRemaining())
					{
						// Get all units that have already attacked
						final List<Unit> alreadyAttackedWithUnits = new ArrayList<Unit>();
						for (final Territory t2 : attackMap.keySet())
							alreadyAttackedWithUnits.addAll(attackMap.get(t2).getUnits());
						
						// Find units that haven't attacked and can be transported
						for (final ProAmphibData proTransportData : transportMapList)
						{
							if (proTransportData.getTransport().equals(transport))
							{
								final Set<Territory> territoriesCanLoadFrom = proTransportData.getTransportMap().get(t);
								amphibUnitsToAdd = transportUtils.getUnitsToTransportFromTerritories(player, transport, territoriesCanLoadFrom, alreadyAttackedWithUnits);
								if (!amphibUnitsToAdd.isEmpty())
								{
									minWinTerritory = t;
									minWinPercentage = result.getWinPercentage();
									break;
								}
							}
						}
					}
				}
			}
			if (minWinTerritory != null)
			{
				attackMap.get(minWinTerritory).addUnits(amphibUnitsToAdd);
				attackMap.get(minWinTerritory).putAmphibAttackMap(transport, amphibUnitsToAdd);
				attackMap.get(minWinTerritory).setBattleResult(null);
				for (final Unit unit : amphibUnitsToAdd)
					sortedUnitAttackOptions.remove(unit);
				LogUtils.log(Level.FINER, "Adding amphibious attack to: " + minWinTerritory.getName() + ", units=" + amphibUnitsToAdd.size());
			}
		}
		
		// Re-sort attack options
		sortedUnitAttackOptions = attackOptionsUtils.sortUnitNeededOptions(player, sortedUnitAttackOptions, attackMap);
		
		// Try to set air units in can't hold territories first (don't move planes to empty territories)
		for (final Iterator<Unit> it = sortedUnitAttackOptions.keySet().iterator(); it.hasNext();)
		{
			final Unit unit = it.next();
			final boolean isAirUnit = UnitAttachment.get(unit.getType()).getIsAir();
			if (!isAirUnit)
				continue; // skip non-air units
			Territory minWinTerritory = null;
			double minWinPercentage = WIN_PERCENTAGE;
			for (final Territory t : sortedUnitAttackOptions.get(unit))
			{
				if (!attackMap.get(t).isCurrentlyWins() && !attackMap.get(t).isCanHold())
				{
					if (attackMap.get(t).getBattleResult() == null)
						attackMap.get(t).setBattleResult(battleUtils.estimateBattleResults(player, t, attackMap.get(t).getUnits()));
					final ProBattleResultData result = attackMap.get(t).getBattleResult();
					if (result.getWinPercentage() < minWinPercentage || (!result.isHasLandUnitRemaining() && minWinTerritory == null))
					{
						final List<Unit> defendingUnits = t.getUnits().getMatches(Matches.enemyUnit(player, data));
						final boolean hasNoDefenders = Match.allMatch(defendingUnits, Matches.UnitIsInfrastructure);
						if (!(defendingUnits.isEmpty() || hasNoDefenders))
						{
							minWinPercentage = result.getWinPercentage();
							minWinTerritory = t;
						}
					}
				}
			}
			if (minWinTerritory != null)
			{
				attackMap.get(minWinTerritory).addUnit(unit);
				attackMap.get(minWinTerritory).setBattleResult(null);
				it.remove();
			}
		}
		
		// Re-sort attack options
		sortedUnitAttackOptions = attackOptionsUtils.sortUnitNeededOptions(player, sortedUnitAttackOptions, attackMap);
		
		// Set remaining units in any territory that needs it (don't move planes to empty territories)
		for (final Iterator<Unit> it = sortedUnitAttackOptions.keySet().iterator(); it.hasNext();)
		{
			final Unit unit = it.next();
			final boolean isAirUnit = UnitAttachment.get(unit.getType()).getIsAir();
			Territory minWinTerritory = null;
			double minWinPercentage = WIN_PERCENTAGE;
			for (final Territory t : sortedUnitAttackOptions.get(unit))
			{
				if (!attackMap.get(t).isCurrentlyWins())
				{
					if (attackMap.get(t).getBattleResult() == null)
						attackMap.get(t).setBattleResult(battleUtils.estimateBattleResults(player, t, attackMap.get(t).getUnits()));
					final ProBattleResultData result = attackMap.get(t).getBattleResult();
					if (result.getWinPercentage() < minWinPercentage || (!result.isHasLandUnitRemaining() && minWinTerritory == null))
					{
						final List<Unit> defendingUnits = t.getUnits().getMatches(Matches.enemyUnit(player, data));
						final boolean hasNoDefenders = Match.allMatch(defendingUnits, Matches.UnitIsInfrastructure);
						if (!isAirUnit || !(defendingUnits.isEmpty() || hasNoDefenders))
						{
							minWinPercentage = result.getWinPercentage();
							minWinTerritory = t;
						}
					}
				}
			}
			if (minWinTerritory != null)
			{
				attackMap.get(minWinTerritory).addUnit(unit);
				attackMap.get(minWinTerritory).setBattleResult(null);
				it.remove();
			}
		}
		
		return sortedUnitAttackOptions;
	}
	
	private void logAttackMoves(final Map<Territory, ProAttackTerritoryData> attackMap, final Map<Unit, Set<Territory>> unitAttackMap, final List<ProAmphibData> transportMapList,
				final List<ProAttackTerritoryData> prioritizedTerritories, final Map<Territory, ProAttackTerritoryData> enemyAttackMap)
	{
		// Print prioritization
		LogUtils.log(Level.FINER, "Prioritized territories:");
		for (final ProAttackTerritoryData attackTerritoryData : prioritizedTerritories)
		{
			LogUtils.log(Level.FINEST, "  " + attackTerritoryData.getTUVSwing() + "  " + attackTerritoryData.getValue() + "  " + attackTerritoryData.getTerritory().getName());
		}
		
		// Print transport map
		LogUtils.log(Level.FINER, "Transport territories:");
		int tcount = 0;
		int count = 0;
		for (final ProAmphibData proTransportData : transportMapList)
		{
			final Map<Territory, Set<Territory>> transportMap = proTransportData.getTransportMap();
			tcount++;
			LogUtils.log(Level.FINEST, "Transport #" + tcount);
			for (final Territory t : transportMap.keySet())
			{
				count++;
				LogUtils.log(Level.FINEST, count + ". Can attack " + t.getName());
				final Set<Territory> territories = transportMap.get(t);
				LogUtils.log(Level.FINEST, "  --- From territories ---");
				for (final Territory fromTerritory : territories)
				{
					LogUtils.log(Level.FINEST, "    " + fromTerritory.getName());
				}
			}
		}
		
		// Print enemy territories with enemy units vs my units
		LogUtils.log(Level.FINER, "Territories that can be attacked:");
		count = 0;
		for (final Territory t : attackMap.keySet())
		{
			count++;
			LogUtils.log(Level.FINEST, count + ". ---" + t.getName());
			final Set<Unit> combinedUnits = new HashSet<Unit>(attackMap.get(t).getMaxUnits());
			combinedUnits.addAll(attackMap.get(t).getMaxAmphibUnits());
			LogUtils.log(Level.FINEST, "  --- My max units ---");
			final Map<String, Integer> printMap = new HashMap<String, Integer>();
			for (final Unit unit : combinedUnits)
			{
				if (printMap.containsKey(unit.toStringNoOwner()))
				{
					printMap.put(unit.toStringNoOwner(), printMap.get(unit.toStringNoOwner()) + 1);
				}
				else
				{
					printMap.put(unit.toStringNoOwner(), 1);
				}
			}
			for (final String key : printMap.keySet())
			{
				LogUtils.log(Level.FINEST, "    " + printMap.get(key) + " " + key);
			}
			final List<Unit> units3 = attackMap.get(t).getUnits();
			LogUtils.log(Level.FINEST, "  --- My actual units ---");
			final Map<String, Integer> printMap3 = new HashMap<String, Integer>();
			for (final Unit unit : units3)
			{
				if (printMap3.containsKey(unit.toStringNoOwner()))
				{
					printMap3.put(unit.toStringNoOwner(), printMap3.get(unit.toStringNoOwner()) + 1);
				}
				else
				{
					printMap3.put(unit.toStringNoOwner(), 1);
				}
			}
			for (final String key : printMap3.keySet())
			{
				LogUtils.log(Level.FINEST, "    " + printMap3.get(key) + " " + key);
			}
			LogUtils.log(Level.FINEST, "  --- Enemy units ---");
			final Map<String, Integer> printMap2 = new HashMap<String, Integer>();
			final List<Unit> units2 = t.getUnits().getMatches(Matches.enemyUnit(player, data));
			for (final Unit unit : units2)
			{
				if (printMap2.containsKey(unit.toStringNoOwner()))
				{
					printMap2.put(unit.toStringNoOwner(), printMap2.get(unit.toStringNoOwner()) + 1);
				}
				else
				{
					printMap2.put(unit.toStringNoOwner(), 1);
				}
			}
			for (final String key : printMap2.keySet())
			{
				LogUtils.log(Level.FINEST, "    " + printMap2.get(key) + " " + key);
			}
			LogUtils.log(Level.FINEST, "  --- Enemy Counter Attack Units ---");
			final Map<String, Integer> printMap4 = new HashMap<String, Integer>();
			final List<Unit> units4 = attackMap.get(t).getMaxEnemyUnits();
			for (final Unit unit : units4)
			{
				if (printMap4.containsKey(unit.toStringNoOwner()))
				{
					printMap4.put(unit.toStringNoOwner(), printMap4.get(unit.toStringNoOwner()) + 1);
				}
				else
				{
					printMap4.put(unit.toStringNoOwner(), 1);
				}
			}
			for (final String key : printMap4.keySet())
			{
				LogUtils.log(Level.FINEST, "    " + printMap4.get(key) + " " + key);
			}
		}
	}
	
}