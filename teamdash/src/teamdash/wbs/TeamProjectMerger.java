// Copyright (C) 2012 Tuma Solutions, LLC
// Team Functionality Add-ons for the Process Dashboard
//
// This program is free software; you can redistribute it and/or
// modify it under the terms of the GNU General Public License
// as published by the Free Software Foundation; either version 3
// of the License, or (at your option) any later version.
//
// Additional permissions also apply; see the README-license.txt
// file in the project root directory for more information.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, see <http://www.gnu.org/licenses/>.
//
// The author(s) may be contacted at:
//     processdash@tuma-solutions.com
//     processdash-devel@lists.sourceforge.net

package teamdash.wbs;

import java.io.File;
import java.util.Map;

import teamdash.team.TeamMemberList;
import teamdash.team.TeamMemberListMerger;
import teamdash.wbs.columns.TeamMemberTimeColumn;

public class TeamProjectMerger {

    private TeamProject base;

    private TeamProject main;

    private TeamProject incoming;

    private TeamProject merged;

    public TeamProjectMerger(TeamProject base, TeamProject main,
            TeamProject incoming) {
        this.base = base;
        this.main = main;
        this.incoming = incoming;
    }

    public TeamProject getMerged() {
        return merged;
    }

    public void run() {
        TeamMemberList team = mergeTeams();
        WorkflowWBSModel workflows = mergeWorkflows();
        MilestonesWBSModel milestones = mergeMilestones();
        WBSModel wbs = mergeWBS();

        File dir = new File("no such directory " + System.currentTimeMillis());
        merged = new TeamProject(dir, "Unused", team, wbs, workflows,
                milestones);
    }

    private TeamMemberList mergeTeams() {
        // calculate the merged team member list.
        TeamMemberListMerger teamMerger = new TeamMemberListMerger(base, main,
                incoming);

        // the team member merge may have caused initials to change in the
        // main and incoming projects. Apply those changes to the WBS.
        changeInitials(main.getWBS(),
            teamMerger.getChangesNeededToMainInitials());
        changeInitials(incoming.getWBS(),
            teamMerger.getChangesNeededToIncomingInitials());

        return teamMerger.getMerged();
    }

    private void changeInitials(WBSModel wbsModel,
            Map<String, String> changesToInitials) {
        if (changesToInitials != null && !changesToInitials.isEmpty())
            TeamMemberTimeColumn.changeInitials(wbsModel, changesToInitials);
    }

    private WorkflowWBSModel mergeWorkflows() {
        WorkflowMerger workflowMerger = new WorkflowMerger(base, main,
                incoming);
        return workflowMerger.getMerged();
    }

    private MilestonesWBSModel mergeMilestones() {
        MilestonesMerger milestonesMerger = new MilestonesMerger(base, main,
                incoming);
        return milestonesMerger.getMerged();
    }

    private WBSModel mergeWBS() {
        WBSMerger wbsMerger = new WBSMerger(base, main, incoming);
        return wbsMerger.getMerged();
    }

}